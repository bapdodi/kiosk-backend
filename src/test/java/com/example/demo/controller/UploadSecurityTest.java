package com.example.demo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.config.SecurityConfig;
import com.example.demo.config.StorageProperties;
import com.example.demo.service.CustomUserDetailsService;
import com.example.demo.service.FileService;

import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import okhttp3.Headers;

@WebMvcTest({ FileUploadController.class, ImageController.class })
@Import(SecurityConfig.class)
class UploadSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileService fileService;

    @MockBean
    private MinioClient minioClient;

    @MockBean
    private StorageProperties storageProperties;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private final MockMultipartFile file =
            new MockMultipartFile("file", "x.html", "text/html", "<script>alert(1)</script>".getBytes());

    @Test
    @DisplayName("로그인하지 않으면 파일을 올릴 수 없다")
    void anonymousUploadIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/upload").file(file))
                .andExpect(status().isForbidden());

        verify(fileService, never()).storeFile(any());
    }

    @Test
    @DisplayName("관리자는 파일을 올릴 수 있다")
    @WithMockUser(roles = "ADMIN")
    void adminCanUpload() throws Exception {
        given(fileService.storeFile(any())).willReturn("a.png");

        mockMvc.perform(multipart("/api/upload").file(file))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("사진이 아닌 파일은 웹페이지로 열지 않고 내려받게 한다")
    void nonImageIsServedAsAttachment() throws Exception {
        stubObject("text/html");

        mockMvc.perform(get("/uploads/x.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment"));
    }

    @Test
    @DisplayName("svg 는 스크립트를 담을 수 있어 이미지여도 내려받게 한다")
    void svgIsServedAsAttachment() throws Exception {
        stubObject("image/svg+xml");

        mockMvc.perform(get("/uploads/x.svg"))
                .andExpect(header().string("Content-Disposition", "attachment"));
    }

    @Test
    @DisplayName("사진은 그대로 화면에 보여 준다")
    void imageIsServedInline() throws Exception {
        stubObject("image/png");

        mockMvc.perform(get("/uploads/x.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "inline"));
    }

    private void stubObject(String contentType) throws Exception {
        byte[] body = "data".getBytes();
        StatObjectResponse stat = mock(StatObjectResponse.class);
        given(stat.contentType()).willReturn(contentType);
        given(stat.size()).willReturn((long) body.length);
        given(storageProperties.getBucket()).willReturn("kiosk");
        given(minioClient.statObject(any())).willReturn(stat);
        given(minioClient.getObject(any())).willReturn(
                new GetObjectResponse(Headers.of(), "kiosk", "", "x", new ByteArrayInputStream(body)));
    }
}
