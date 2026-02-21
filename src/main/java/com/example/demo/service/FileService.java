package com.example.demo.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {

    private static final String UPLOAD_DIR = "uploads";

    public String storeFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        String fileName = UUID.randomUUID().toString() + "_" + originalFileName;
        Path filePath = uploadPath.resolve(fileName);

        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        return fileName;
    }

    public String getFileUrl(String fileName) {
        return "/uploads/" + fileName;
    }

    public String renameFile(String oldFileName, String newFileName) throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        Path oldPath = uploadPath.resolve(oldFileName);
        Path newPath = uploadPath.resolve(newFileName);

        if (Files.exists(oldPath)) {
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
            return newFileName;
        }
        return oldFileName;
    }
}
