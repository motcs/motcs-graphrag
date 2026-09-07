package com.motcs.commons.utils;

import jakarta.annotation.Nonnull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于字节数组的 MultipartFile 实现，用于远程 URL 下载文件后复用上传流程。
 * 不依赖 spring-test 的 MockMultipartFile，可在生产环境使用。
 */
public class ByteArrayMultipartFile implements MultipartFile {

    private final String name;

    private final String originalFilename;

    private final String contentType;

    private final byte[] content;

    public ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    @Override
    @Nonnull
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    @Nonnull
    public byte[] getBytes() {
        return content;
    }

    @Override
    @Nonnull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(@Nonnull File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }

    @Override
    public void transferTo(@Nonnull Path dest) throws IOException {
        Files.write(dest, content);
    }
}
