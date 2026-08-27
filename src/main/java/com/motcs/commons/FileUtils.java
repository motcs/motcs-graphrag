package com.motcs.commons;

import lombok.extern.log4j.Log4j2;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文件操作工具
 */
@Log4j2
public class FileUtils {

    /**
     * 保存文件到本地
     *
     * @param file 上传的文件
     * @return 文件保存路径
     * @throws IOException IO异常
     */
    public static String saveFile(MultipartFile file, String uploadDir, String tenantCode) throws IOException {
        // 基础上传目录 + tenantCode子目录
        Path uploadPath = Paths.get(uploadDir, "T%s".formatted(tenantCode));
        // 创建多级目录
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // 获取原始文件名，做非空保护
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IOException("文件原始名称不能为空");
        }

        // 安全处理文件名：防止路径穿越，只取文件名，剔除路径部分
        String safeFileName = Paths.get(originalFilename).getFileName().toString();

        // 拼接完整文件路径 uploadDir/tenantCode/原文件名
        Path filePath = uploadPath.resolve(safeFileName);
        // 保存文件，存在则覆盖
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        // 返回完整磁盘路径，如果需要返回相对路径可自行修改 return tenantCode + "/" + safeFileName;
        return filePath.toString();
    }

    /**
     * 读取文件内容
     *
     * @param file 上传的文件
     * @return 文件内容
     * @throws IOException IO异常
     */
    public static String readFileContent(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return new String(inputStream.readAllBytes());
        }
    }

    /**
     * 校验文件
     *
     * @param file 上传的文件
     */
    public static void validateFile(MultipartFile file, long maxFileSize) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // 校验文件大小
        if (file.getSize() > maxFileSize) {
            String formatted = String.format("文件大小超过限制，最大允许 %d MB", maxFileSize / 1024 / 1024);
            throw new IllegalArgumentException(formatted);
        }

        // 校验文件类型
        String fileName = file.getOriginalFilename();

        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        if (!Utils.isFormatSupported(fileName)) {
            String noFormat = "不支持的文件类型，仅支持: " + String.join(", ", Utils.SUPPORTED_FORMATS);
            throw new IllegalArgumentException(noFormat);
        }
    }

    /**
     * 解析文档ID
     *
     * @param documentId 字符串形式的文档ID
     * @return Long类型的文档ID
     */
    public static Long parseDocumentId(String documentId) {
        try {
            // 使用UUID的hashCode作为文档ID
            return (long) Math.abs(UUID.fromString(documentId).hashCode());
        } catch (IllegalArgumentException e) {
            log.error("解析文档ID失败: {}", documentId);
            return null;
        }
    }

    /**
     * 从metadata中解析上传时间
     *
     * @param uploadTimeObj metadata中的uploadTime值
     * @return LocalDateTime或null
     */
    public static LocalDateTime parseUploadTime(Object uploadTimeObj) {
        if (uploadTimeObj == null) {
            return null;
        }
        String str;
        if (uploadTimeObj instanceof org.neo4j.driver.Value v) {
            str = v.asString(null);
        } else {
            str = uploadTimeObj.toString();
        }
        if (str == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(str);
        } catch (Exception e) {
            log.warn("解析上传时间失败: {}", str);
            return null;
        }
    }

    /**
     * 从分片文本中提取页码范围（跨页时返回 "起始-结束"，单页返回 "页码"）
     *
     * @param text 分片文本
     * @return 页码范围，如 "1" 或 "1-2"，未找到时返回 "1"
     */
    public static String extractPageRange(String text) {
        if (text == null || text.isEmpty()) return "1";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[\\[PAGE:(\\d+)]]");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        int first = -1, last = -1;
        while (matcher.find()) {
            int p = Integer.parseInt(matcher.group(1));
            if (first == -1) first = p;
            last = p;
        }
        if (first == -1) return "1";
        return first == last ? String.valueOf(first) : first + "-" + last;
    }

    /**
     * 从文本中移除所有 [[PAGE:n]] 标记
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String stripPageMarkers(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\[\\[PAGE:\\d+]]\\s*", "").trim();
    }
}
