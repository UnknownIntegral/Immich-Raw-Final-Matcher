package com.photocull.matcher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileScanner {
    private static final Set<String> RAW_EXTENSIONS = Set.of(
            "cr2", "cr3", "arw", "dng", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "x3f"
    );
    private static final Set<String> FINISHED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    public List<PhotoFile> scanRawFiles(Path root) throws IOException {
        return scan(root, RAW_EXTENSIONS);
    }

    public List<PhotoFile> scanFinishedFiles(Path root) throws IOException {
        return scan(root, FINISHED_EXTENSIONS);
    }

    private List<PhotoFile> scan(Path root, Set<String> extensions) throws IOException {
        List<PhotoFile> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && extensions.contains(extension(file))) {
                    try {
                        files.add(PhotoFile.from(file, root));
                    } catch (IOException ignored) {
                        // A single unreadable file should not stop a large scan.
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private String extension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

