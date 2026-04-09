package ca.pfv.spmf.server.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;
/*
 *  Copyright (c) 2026 Philippe Fournier-Viger
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF.  If not, see <http://www.gnu.org/licenses/>.
 */
/** File-system utilities — zero external dependencies. */
public final class FileUtil {

    private static final Logger log = ServerLogger.get(FileUtil.class);

    private FileUtil() {}

    /** Recursively delete a directory tree. Silently ignores errors. */
    public static void deleteDirectory(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.fine("Deleted directory: " + dirPath);
        } catch (IOException e) {
            log.warning("Could not fully delete directory " + dirPath +
                        ": " + e.getMessage());
        }
    }

    /** Create directory (and parents) if it does not already exist. */
    public static void ensureDirectory(String dirPath) throws IOException {
        Files.createDirectories(Paths.get(dirPath));
    }
}