package ca.pfv.spmf.server.util;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 *
 * This file is part of the SPMF SERVER
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPMF is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;

/**
 * File-system utility methods for the SPMF-Server.
 * <p>
 * All methods in this class have zero external dependencies and operate
 * solely on the {@link java.nio.file} API.
 *
 * @author Philippe Fournier-Viger
 */
public final class FileUtil {

    /** Logger for file-system operation events. */
    private static final Logger log = ServerLogger.get(FileUtil.class);

    /** Prevent instantiation — all methods are static. */
    private FileUtil() {}

    /**
     * Recursively delete a directory tree, including the root directory itself.
     * <p>
     * Errors encountered while deleting individual files or sub-directories are
     * logged as warnings but do not propagate as exceptions, so the caller is
     * never interrupted by partial-deletion failures.
     * <p>
     * If {@code dirPath} does not exist this method is a no-op.
     *
     * @param dirPath path to the directory to delete (absolute or relative)
     */
    public static void deleteDirectory(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            return; // nothing to do
        }

        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {

                /**
                 * Delete each regular file encountered during the walk.
                 * Errors are re-thrown so {@link Files#walkFileTree} can log
                 * them via the {@code visitFileFailed} callback (default
                 * behaviour of {@link SimpleFileVisitor}).
                 */
                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * Delete each directory after its contents have been removed.
                 * If {@code exc} is non-null the directory listing failed; we
                 * re-throw to let the framework handle it.
                 */
                @Override
                public FileVisitResult postVisitDirectory(Path dir,
                                                          IOException exc)
                        throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });

            log.fine("Deleted directory tree: " + dirPath);

        } catch (IOException e) {
            log.warning("Could not fully delete directory '"
                    + dirPath + "': " + e.getMessage());
        }
    }

    /**
     * Create the specified directory and all missing parent directories.
     * <p>
     * This is equivalent to {@code mkdir -p} on Unix. If the directory already
     * exists the method returns silently.
     *
     * @param dirPath path of the directory to create (absolute or relative)
     * @throws IOException if the directory (or its parents) cannot be created
     */
    public static void ensureDirectory(String dirPath) throws IOException {
        Files.createDirectories(Paths.get(dirPath));
    }
}