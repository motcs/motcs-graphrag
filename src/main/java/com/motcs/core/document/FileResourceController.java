package com.motcs.core.document;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态文件代理接口。
 *
 * <p>通过 HTTP 读取文件根目录下的文件（图片、PDF、Office 文档等），供 nginx 反向代理对外提供访问。
 * 根目录复用 {@code app.file.upload-dir}（由环境变量 {@code FILE_UPLOAD_DIR} 配置，默认 {@code ./uploads}），
 * 因此文件可位于局域网内应用可访问的任意路径。
 *
 * <p>访问路径：{@code GET /files/v1/{相对路径}}，相对路径相对文件根目录，
 * 例如 {@code /files/v1/T0/20260920D001.docx} 对应 {@code {upload-dir}/T0/20260920D001.docx}。
 * 已做路径穿越（{@code ..}）校验，仅允许访问根目录内的文件；文件不存在或越界返回 404。
 * 支持 Range 请求（浏览器播放/断点），响应 Content-Type 按文件扩展名由 Spring 自动推断。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 */
@Log4j2
@RestController
@RequestMapping("/files/v1")
public class FileResourceController {

    /** 接口前缀，与 {@code @RequestMapping} 保持一致，用于从请求路径中剥离相对路径 */
    private static final String PREFIX = "/files/v1";

    /** 静态文件根目录：复用 app.file.upload-dir（环境变量 FILE_UPLOAD_DIR，默认 ./uploads） */
    @Value("${app.file.upload-dir:./uploads}")
    private String uploadDir;

    @GetMapping("/**")
    public Mono<ResponseEntity<Resource>> serve(ServerWebExchange exchange) {
        String fullPath = exchange.getRequest().getPath().value();
        if (!fullPath.startsWith(PREFIX)) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        // 剥离接口前缀，得到相对文件根目录的路径（去掉开头多余的 "/"）
        String relative = fullPath.substring(PREFIX.length()).replaceFirst("^/+", "");

        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();

        // 路径穿越防护：解析后的目标必须位于根目录内
        if (!target.startsWith(root)) {
            log.debug("静态文件访问被拒绝，路径越界: {}", fullPath);
            return Mono.just(ResponseEntity.notFound().build());
        }
        if (!Files.isRegularFile(target)) {
            log.debug("静态文件不存在: {}", target);
            return Mono.just(ResponseEntity.notFound().build());
        }

        Resource resource = new FileSystemResource(target);
        return Mono.just(ResponseEntity.ok().body(resource));
    }
}
