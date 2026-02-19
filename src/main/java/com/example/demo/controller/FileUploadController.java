package com.example.demo.controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.service.FileService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileService fileService;

    @PostMapping
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select a file to upload");
        }

        try {
            String fileName = fileService.storeFile(file);
            String fileDownloadUri = fileService.getFileUrl(fileName);

            return ResponseEntity.ok(new UploadResponse(fileName, fileDownloadUri));
        } catch (IOException ex) {
            return ResponseEntity.internalServerError().body("Could not upload file: " + ex.getMessage());
        }
    }

    record UploadResponse(String fileName, String fileUrl) {
    }
}
