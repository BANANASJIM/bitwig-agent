package dev.bitwigagent.render;

import com.google.gson.JsonObject;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.project.ProjectSaveAsPlan;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Host-free export.audio targets and output checks. Bitwig 6.1.4 is given extensionless base paths: the WAV writer
 * (WuK) appends ".wav" and the encoder (dip) appends "." + fileType, so the files are base + ".wav" / ".flac"; mp3,
 * ogg and opus are expected the same way (base + ".mp3" / ".ogg" / ".opus"), not yet confirmed on a native host.
 */
final class RenderPlan {
    static final int MAX_NAME = 128;
    static final Set<String> COMPRESSED = Set.of("mp3", "ogg", "opus");
    final Path directory;
    final List<Path> bases = new ArrayList<>(), files = new ArrayList<>();
    /** Per file: its type and the index of the source it renders. */
    final List<String> types = new ArrayList<>();

    final List<Integer> sources = new ArrayList<>();
    final List<ProjectSaveAsPlan.Stat> before = new ArrayList<>();

    private RenderPlan(Path directory) {
        this.directory = directory;
    }

    static String baseName(String name) {
        if (name.isEmpty()
                || name.length() > MAX_NAME
                || !name.equals(name.strip())
                || name.startsWith(".")
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.codePoints().anyMatch(Character::isISOControl))
            throw Errors.invalid("baseName must be 1.." + MAX_NAME
                    + " characters without '/', '\\', control characters, a leading dot or surrounding whitespace.");
        return name;
    }

    /** One file per source and type: baseName for one source, baseName-01, -02, ... in source order for several. */
    static RenderPlan export(Path directory, String baseName, int sources, List<String> types, boolean overwrite) {
        if (!Files.isDirectory(directory))
            throw Errors.notApplied(
                    Reason.INVALID_TARGET, "directory " + directory + " does not exist or is not a directory.");
        if (!Files.isWritable(directory))
            throw Errors.notApplied(Reason.INVALID_TARGET, "directory " + directory + " is not writable.");
        RenderPlan plan = new RenderPlan(directory);
        for (int i = 0; i < sources; i++) {
            Path base = directory.resolve(sources == 1 ? baseName : baseName + String.format("-%02d", i + 1));
            plan.bases.add(base);
            for (String type : types) {
                plan.files.add(directory.resolve(base.getFileName() + "." + type));
                plan.types.add(type);
                plan.sources.add(i);
            }
        }
        plan.refresh(overwrite);
        return plan;
    }

    /** Re-run the existence rules and take the pre-dispatch observation (called again right before dispatch). */
    void refresh(boolean overwrite) {
        before.clear();
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || Files.isDirectory(file))
                throw Errors.notApplied(Reason.INVALID_TARGET, file + " is a directory or symbolic link.");
            ProjectSaveAsPlan.Stat stat = ProjectSaveAsPlan.Stat.of(file);
            if (stat.exists && !overwrite)
                throw Errors.notApplied(
                        Reason.TARGET_EXISTS, file + " already exists; pass overwrite:true to replace it.");
            before.add(stat);
        }
    }

    String[] basePaths() {
        String[] out = new String[bases.size()];
        for (int i = 0; i < out.length; i++) out[i] = bases.get(i).toString();
        return out;
    }

    /**
     * Header facts of one output; problem is null only for a new/changed, nonempty, parseable file of the expected kind.
     * mp3/ogg/opus are only identified by their container and codec signature, never decoded: no bit depth or frames.
     */
    static JsonObject inspect(Path file, ProjectSaveAsPlan.Stat before, String fileType, int bitDepth, int sampleRate) {
        JsonObject out = new JsonObject();
        out.addProperty("file", file.toString());
        ProjectSaveAsPlan.Stat now = ProjectSaveAsPlan.Stat.of(file);
        out.addProperty("exists", now.exists);
        if (now.exists) out.addProperty("bytes", now.size);
        boolean written = now.writtenSince(before);
        out.addProperty("written", written);
        String problem = !now.exists ? "missing" : !written ? "not_written_by_this_export" : null;
        if (problem == null) {
            try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
                problem = COMPRESSED.contains(fileType)
                        ? compressed(in, fileType, out)
                        : fileType.equals("wav") ? wav(in, out) : flac(in, out);
            } catch (IOException error) {
                problem = "unreadable: " + error.getMessage();
            }
        }
        boolean pcm = !COMPRESSED.contains(fileType);
        if (problem == null && pcm && out.get("bitDepth").getAsInt() != bitDepth) problem = "bit_depth_mismatch";
        if (problem == null && pcm && sampleRate != 0 && out.get("sampleRate").getAsInt() != sampleRate)
            problem = "sample_rate_mismatch";
        if (problem == null && out.has("frames") && out.get("frames").getAsLong() <= 0) problem = "no_audio_frames";
        if (problem == null && out.has("frames"))
            out.addProperty(
                    "durationSeconds",
                    out.get("frames").getAsLong()
                            / (double) out.get("sampleRate").getAsInt());
        out.addProperty("valid", problem == null);
        if (problem != null) out.addProperty("problem", problem);
        return out;
    }

    private static String wav(RandomAccessFile in, JsonObject out) throws IOException {
        if (in.length() < 12 || !tag(in).equals("RIFF")) return "not_riff_wav";
        le(in, 4);
        if (!tag(in).equals("WAVE")) return "not_riff_wav";
        int format = -1, channels = 0, rate = 0, bits = 0, block = 0;
        while (in.getFilePointer() + 8 <= in.length()) {
            String id = tag(in);
            long size = le(in, 4), start = in.getFilePointer();
            if (id.equals("fmt ") && size >= 16) {
                format = (int) le(in, 2);
                channels = (int) le(in, 2);
                rate = (int) le(in, 4);
                le(in, 4);
                block = (int) le(in, 2);
                bits = (int) le(in, 2);
                if (format == 0xFFFE && size >= 40) {
                    le(in, 2);
                    le(in, 2);
                    le(in, 4);
                    format = (int) le(in, 2);
                }
            } else if (id.equals("data")) {
                if (format < 0 || block <= 0) return "data_before_fmt";
                if (format != 1 && format != 3) return "unsupported_wav_encoding";
                long available = Math.min(size, in.length() - start);
                out.addProperty("fileType", "wav");
                out.addProperty("encoding", format == 3 ? "float" : "pcm");
                out.addProperty("channels", channels);
                out.addProperty("sampleRate", rate);
                out.addProperty("bitDepth", bits);
                out.addProperty("frames", available / block);
                return available < size ? "truncated_data_chunk" : null;
            }
            in.seek(start + size + (size & 1));
        }
        return "no_data_chunk";
    }

    /**
     * The signature an encoder writes first: an ID3v2 tag or an MPEG audio frame sync for mp3; an Ogg page whose first
     * packet is the Vorbis or Opus identification header for ogg/opus.
     */
    private static String compressed(RandomAccessFile in, String fileType, JsonObject out) throws IOException {
        byte[] head = new byte[Math.min(64, (int) in.length())];
        in.readFully(head);
        String codec;
        if (fileType.equals("mp3")) {
            boolean id3 = head.length >= 3 && new String(head, 0, 3, StandardCharsets.ISO_8859_1).equals("ID3");
            boolean sync = head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
            if (!id3 && !sync) return "not_mp3";
            codec = "mp3";
        } else {
            if (head.length < 28 || !new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("OggS"))
                return "not_ogg";
            int packet = 27 + (head[26] & 0xFF);
            String vorbis = "\u0001vorbis", opus = "OpusHead", wanted = fileType.equals("ogg") ? vorbis : opus;
            if (!startsWith(head, packet, wanted))
                return startsWith(head, packet, vorbis) || startsWith(head, packet, opus)
                        ? "codec_mismatch"
                        : "not_ogg";
            codec = fileType.equals("ogg") ? "vorbis" : "opus";
        }
        out.addProperty("fileType", fileType);
        out.addProperty("codec", codec);
        return null;
    }

    private static boolean startsWith(byte[] head, int at, String magic) {
        return at + magic.length() <= head.length
                && new String(head, at, magic.length(), StandardCharsets.ISO_8859_1).equals(magic);
    }

    private static String flac(RandomAccessFile in, JsonObject out) throws IOException {
        if (in.length() < 42 || !tag(in).equals("fLaC")) return "not_flac";
        int header = in.readUnsignedByte();
        if ((header & 0x7F) != 0) return "flac_streaminfo_missing";
        in.skipBytes(3 + 10);
        long packed = in.readLong();
        int rate = (int) (packed >>> 44),
                channels = (int) ((packed >>> 41) & 7) + 1,
                bits = (int) ((packed >>> 36) & 31) + 1;
        long frames = packed & 0xFFFFFFFFFL;
        out.addProperty("fileType", "flac");
        out.addProperty("channels", channels);
        out.addProperty("sampleRate", rate);
        out.addProperty("bitDepth", bits);
        if (frames > 0) out.addProperty("frames", frames); // 0 = total not recorded in STREAMINFO
        return in.length() <= 42 + 4 ? "no_audio_frames" : null;
    }

    private static String tag(RandomAccessFile in) throws IOException {
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static long le(RandomAccessFile in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) value |= (long) in.readUnsignedByte() << (8 * i);
        return value;
    }
}
