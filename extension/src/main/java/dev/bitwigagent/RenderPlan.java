package dev.bitwigagent;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static dev.bitwigagent.HostServices.failure;

/**
 * Host-free export.audio targets and output checks. Bitwig 6.1.4 is given extensionless base paths: the WAV writer
 * (WuK) appends ".wav" and the encoder (dip) appends "." + format, so the files are base + ".wav" / ".flac".
 */
final class RenderPlan {
    static final int MAX_NAME = 128;
    final Path directory;
    final List<Path> bases = new ArrayList<>(), files = new ArrayList<>();
    final List<ProjectSaveAsPlan.Stat> before = new ArrayList<>();
    final String format;

    private RenderPlan(Path directory, String format) { this.directory = directory; this.format = format; }

    static String baseName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME || !name.equals(name.strip()) || name.startsWith(".")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("baseName must be 1.." + MAX_NAME + " characters without '/', '\\', control characters, a leading dot or surrounding whitespace.");
        return name;
    }

    /** One file per source: baseName for one source, baseName-01, -02, ... in source order for several. */
    static RenderPlan export(Path directory, String baseName, int sources, String format, boolean overwrite) {
        if (!Files.isDirectory(directory)) throw failure("invalid_target", "directory " + directory + " does not exist or is not a directory.");
        if (!Files.isWritable(directory)) throw failure("invalid_target", "directory " + directory + " is not writable.");
        RenderPlan plan = new RenderPlan(directory, format);
        for (int i = 0; i < sources; i++) {
            Path base = directory.resolve(sources == 1 ? baseName : baseName + String.format("-%02d", i + 1));
            Path file = directory.resolve(base.getFileName() + "." + format);
            plan.bases.add(base); plan.files.add(file);
        }
        plan.refresh(overwrite);
        return plan;
    }

    /** Re-run the existence rules and take the pre-dispatch observation (called again right before dispatch). */
    void refresh(boolean overwrite) {
        before.clear();
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || Files.isDirectory(file)) throw failure("invalid_target", file + " is a directory or symbolic link.");
            ProjectSaveAsPlan.Stat stat = ProjectSaveAsPlan.Stat.of(file);
            if (stat.exists && !overwrite) throw failure("target_exists", file + " already exists; pass overwrite:true to replace it.");
            before.add(stat);
        }
    }

    String[] basePaths() {
        String[] out = new String[bases.size()];
        for (int i = 0; i < out.length; i++) out[i] = bases.get(i).toString();
        return out;
    }

    /** Header facts of one output; problem is null only for a new/changed, nonempty, parseable file of the expected kind. */
    static JsonObject inspect(Path file, ProjectSaveAsPlan.Stat before, String format, int bitDepth, int sampleRate) {
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
                problem = format.equals("wav") ? wav(in, out) : flac(in, out);
            } catch (IOException error) { problem = "unreadable: " + error.getMessage(); }
        }
        if (problem == null && out.get("bitDepth").getAsInt() != bitDepth) problem = "bit_depth_mismatch";
        if (problem == null && sampleRate != 0 && out.get("sampleRate").getAsInt() != sampleRate) problem = "sample_rate_mismatch";
        if (problem == null && out.has("frames") && out.get("frames").getAsLong() <= 0) problem = "no_audio_frames";
        if (problem == null && out.has("frames")) out.addProperty("durationSeconds", out.get("frames").getAsLong() / (double) out.get("sampleRate").getAsInt());
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
                format = (int) le(in, 2); channels = (int) le(in, 2); rate = (int) le(in, 4); le(in, 4);
                block = (int) le(in, 2); bits = (int) le(in, 2);
                if (format == 0xFFFE && size >= 40) { le(in, 2); le(in, 2); le(in, 4); format = (int) le(in, 2); }
            } else if (id.equals("data")) {
                if (format < 0 || block <= 0) return "data_before_fmt";
                if (format != 1 && format != 3) return "unsupported_wav_encoding";
                long available = Math.min(size, in.length() - start);
                out.addProperty("format", "wav");
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

    private static String flac(RandomAccessFile in, JsonObject out) throws IOException {
        if (in.length() < 42 || !tag(in).equals("fLaC")) return "not_flac";
        int header = in.readUnsignedByte();
        if ((header & 0x7F) != 0) return "flac_streaminfo_missing";
        in.skipBytes(3 + 10);
        long packed = in.readLong();
        int rate = (int) (packed >>> 44), channels = (int) ((packed >>> 41) & 7) + 1, bits = (int) ((packed >>> 36) & 31) + 1;
        long frames = packed & 0xFFFFFFFFFL;
        out.addProperty("format", "flac");
        out.addProperty("channels", channels);
        out.addProperty("sampleRate", rate);
        out.addProperty("bitDepth", bits);
        if (frames > 0) out.addProperty("frames", frames); // 0 = total not recorded in STREAMINFO
        return in.length() <= 42 + 4 ? "no_audio_frames" : null;
    }

    private static String tag(RandomAccessFile in) throws IOException {
        byte[] bytes = new byte[4]; in.readFully(bytes); return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    private static long le(RandomAccessFile in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) value |= (long) in.readUnsignedByte() << (8 * i);
        return value;
    }
    private static RpcException invalid(String message) { return new RpcException("invalid_params", message, "not_applied"); }
}
