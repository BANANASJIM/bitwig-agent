package dev.bitwigagent;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Host-free resolution of a project.saveAs target. Bitwig 6.1.4's save job (ewo.run, iYN.Hnt) keeps a file whose
 * parent lies inside a project folder (an ancestor holding ".bitwig-project") where it is, and otherwise creates
 * "parent/Name/" and writes "parent/Name/Name.bwproject". The requested path is always the final file, so a new
 * project must be named "dir/Name/Name.bwproject" and is dispatched as "dir/Name.bwproject".
 */
final class ProjectSaveAsPlan {
    static final String EXTENSION = ".bwproject", MARKER = ".bitwig-project", BACKUPS = "auto-backups";
    static final int MAX_NAME = 128;
    final Path file, folder, dispatchPath;
    final String name;
    final boolean newFolder;
    final Stat before;

    private ProjectSaveAsPlan(Path file, Path folder, Path dispatchPath, String name, boolean newFolder) {
        this.file = file;
        this.folder = folder;
        this.dispatchPath = dispatchPath;
        this.name = name;
        this.newFolder = newFolder;
        before = Stat.of(file);
    }

    /** Static shape rules; also rejects the native template and auto-backups branches. */
    static String projectName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null || path.getParent() == null || path.getParent().getParent() == null)
            throw Errors.invalid("path must name a .bwproject file inside a folder.");
        String text = fileName.toString();
        if (!text.endsWith(EXTENSION)) throw Errors.invalid("path must end with " + EXTENSION + ".");
        String name = text.substring(0, text.length() - EXTENSION.length());
        if (name.isEmpty()
                || name.length() > MAX_NAME
                || !name.equals(name.strip())
                || name.startsWith(".")
                || name.equalsIgnoreCase("template"))
            throw Errors.invalid("Project name must be 1.." + MAX_NAME
                    + " characters without leading/trailing whitespace or a leading dot, and not 'template'.");
        for (Path part : path)
            if (part.toString().equals(BACKUPS))
                throw Errors.invalid("Bitwig refuses to save inside an auto-backups folder.");
        return name;
    }

    static ProjectSaveAsPlan resolve(Path path, Path projectsRoot, Path home, boolean overwrite, boolean allowAnyPath) {
        String name = projectName(path);
        Path root = projectsRoot == null ? null : projectsRoot.toAbsolutePath().normalize();
        if (!allowAnyPath) {
            if (root == null || !Files.isDirectory(root))
                throw Errors.notApplied(
                        ErrorCode.PATH_NOT_ALLOWED,
                        "Bitwig's projects folder is unknown or missing (" + root
                                + "); pass allowAnyPath:true to save elsewhere.");
            Path real = real(path), realRoot = real(root);
            if (!real.startsWith(realRoot) || real.equals(realRoot))
                throw Errors.notApplied(
                        ErrorCode.PATH_NOT_ALLOWED,
                        "Path is outside Bitwig's projects folder " + root + "; use "
                                + String.join(File.separator, root.toString(), "<Name>", "<Name>" + EXTENSION)
                                + " or pass allowAnyPath:true.");
        }
        Path parent = path.getParent(), owner = projectFolder(parent, root, home);
        ProjectSaveAsPlan plan;
        if (owner != null) {
            if (!Files.isDirectory(parent))
                throw Errors.notApplied(
                        ErrorCode.INVALID_TARGET,
                        "Folder " + parent + " inside project folder " + owner + " does not exist.");
            plan = new ProjectSaveAsPlan(path, owner, path, name, false);
        } else {
            if (!parent.getFileName().toString().equals(name))
                throw Errors.notApplied(
                        ErrorCode.INVALID_TARGET,
                        "A new project must be <dir>/" + name + "/" + name + EXTENSION + " (Bitwig creates the " + name
                                + " project folder), or a file inside an existing project folder.");
            Path container = parent.getParent();
            if (!Files.isDirectory(container))
                throw Errors.notApplied(ErrorCode.INVALID_TARGET, "Folder " + container + " does not exist.");
            boolean exists = Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
            if (exists && !emptyDirectory(parent))
                throw Errors.notApplied(
                        ErrorCode.TARGET_EXISTS,
                        parent + " already exists and is not a Bitwig project folder (no " + MARKER + ").");
            plan = new ProjectSaveAsPlan(path, parent, container.resolve(path.getFileName()), name, !exists);
        }
        if (Files.isSymbolicLink(path) || Files.isDirectory(path))
            throw Errors.notApplied(ErrorCode.INVALID_TARGET, path + " is a directory or symbolic link.");
        if (plan.before.exists && !overwrite)
            throw Errors.notApplied(
                    ErrorCode.TARGET_EXISTS,
                    path
                            + " already exists; pass overwrite:true (Bitwig moves the old file into the project's auto-backups folder).");
        return plan;
    }

    /**
     * Nearest ancestor holding the marker. Bitwig ignores (and deletes) markers only in home, the projects root and
     * two other fixed folders; a marker anywhere else above the root would be honoured, so it is refused, not guessed.
     */
    static Path projectFolder(Path start, Path root, Path home) {
        Path homePath = home == null ? null : home.toAbsolutePath().normalize();
        for (Path folder = start; folder != null; folder = folder.getParent()) {
            if (folder.equals(homePath)
                    || folder.equals(root)
                    || !Files.isDirectory(folder)
                    || !Files.exists(folder.resolve(MARKER))) continue;
            if (root != null && root.startsWith(folder))
                throw Errors.notApplied(
                        ErrorCode.INVALID_TARGET,
                        "Stray " + MARKER + " in " + folder
                                + " above the projects folder would make Bitwig treat it as the project folder; remove it first.");
            return folder;
        }
        return null;
    }

    private static boolean emptyDirectory(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            return !entries.iterator().hasNext();
        } catch (IOException error) {
            return false;
        }
    }

    /** Real path of the nearest existing ancestor plus the not-yet-existing remainder. */
    static Path real(Path path) {
        Path existing = path, rest = path.getFileSystem().getPath("");
        while (existing != null && !Files.exists(existing)) {
            rest = existing.getFileName().resolve(rest);
            existing = existing.getParent();
        }
        try {
            return existing == null ? path : existing.toRealPath().resolve(rest).normalize();
        } catch (IOException error) {
            throw Errors.notApplied(ErrorCode.INVALID_TARGET, "Cannot resolve " + path + ": " + error.getMessage());
        }
    }

    /** File evidence: written means present, nonempty and different from the pre-dispatch observation. */
    static final class Stat {
        final boolean exists;
        final long size, modified;

        private Stat(boolean exists, long size, long modified) {
            this.exists = exists;
            this.size = size;
            this.modified = modified;
        }

        static Stat of(Path file) {
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Stat(false, -1, -1);
                return new Stat(
                        true,
                        Files.size(file),
                        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                                .toMillis());
            } catch (IOException error) {
                return new Stat(false, -1, -1);
            }
        }

        boolean writtenSince(Stat before) {
            return exists && size > 0 && (!before.exists || modified != before.modified || size != before.size);
        }
    }
}
