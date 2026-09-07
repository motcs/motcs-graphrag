package com.motcs.commons.utils;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 通用工具类
 */
public class Utils {

    /**
     * 支持的文件格式列表
     * 从环境变量 SUPPORTED_FORMATS 读取（逗号分隔），未设置时使用默认值
     */
    public static List<String> SUPPORTED_FORMATS;

    static {
        String envFormats = System.getenv("SUPPORTED_FORMATS");
        if (envFormats != null && !envFormats.isBlank()) {
            SUPPORTED_FORMATS = Arrays.stream(envFormats.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } else {
            SUPPORTED_FORMATS = List.of("pdf",
                    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md");
        }
    }

    /**
     * 从 URL 中提取文件名
     */
    public static String extractFileNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            String fileName = Path.of(path).getFileName().toString();
            if (!fileName.isBlank()) {
                return fileName;
            }
        } catch (Exception ignored) {
        }
        return "downloaded-file";
    }

    /**
     * 探测文件 Content-Type，失败则返回 application/octet-stream
     */
    public static String filesProbeContentType(String fileName) {
        try {
            String type = java.nio.file.Files.probeContentType(Path.of(fileName));
            return type != null ? type : "application/octet-stream";
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    /**
     * 获取文件扩展名
     *
     * @param filename 文件名
     * @return 扩展名
     */
    public static String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }

        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(lastDotIndex + 1);
    }

    /**
     * 检查文件格式是否支持
     */
    public static boolean isFormatSupported(String fileName) {
        String extension = Utils.getFileExtension(fileName).toLowerCase();
        return SUPPORTED_FORMATS.contains(extension);
    }

    /**
     * 获取文件类型
     *
     * @param fileName 文件名
     * @return 文件类型
     */
    public static String getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "UNKNOWN";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "UNKNOWN";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        return switch (extension) {
            case "txt", "csv" -> "TEXT";
            case "md" -> "MARKDOWN";
            case "pdf" -> "PDF";
            case "doc", "docx" -> "WORD";
            case "xls", "xlsx" -> "EXCEL";
            case "ppt", "pptx" -> "POWERPOINT";
            default -> "UNKNOWN";
        };
    }

    /**
     * 将 DataBuffer 列表合并为单个 byte[]，并释放 buffer 避免内存泄漏
     */
    public static byte[] concatenateBuffers(List<DataBuffer> buffers) {
        int total = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
        byte[] bytes = new byte[total];
        int offset = 0;
        for (DataBuffer buffer : buffers) {
            int count = buffer.readableByteCount();
            buffer.read(bytes, offset, count);
            offset += count;
            DataBufferUtils.release(buffer);
        }
        return bytes;
    }
}
