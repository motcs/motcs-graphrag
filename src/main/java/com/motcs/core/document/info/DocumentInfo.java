package com.motcs.core.document.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档元数据表（MySQL R2DBC）：记录每个上传文档的元信息。
 * <p>
 * 文档列表/搜索/筛选直接查本表，Neo4j 只负责向量检索与知识图谱。
 * 删除文档时本表记录与 Neo4j 分片/向量/原始文件级联删除。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-19 星期六
 */
@Schema(description = "文档元数据")
@Table("document_info")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInfo implements Serializable {

    @Id
    private Long id;

    @Column("document_id")
    private String documentId;

    @Column("doc_code")
    private String docCode;

    @Column("tenant_code")
    private String tenantCode;

    @Column("system_type")
    private String systemType;

    @Column("file_name")
    private String fileName;

    @Column("stored_file_name")
    private String storedFileName;

    @Column("title")
    private String title;

    @Column("description")
    private String description;

    @Column("file_size")
    private Long fileSize;

    @Column("file_path")
    private String filePath;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    @Column("chunk_count")
    private Integer chunkCount;

    @Column("enabled")
    private Boolean enabled;

    @Column("user_id")
    private String userId;

    @Column("created_time")
    private LocalDateTime createdTime;

    @Column("updated_time")
    private LocalDateTime updatedTime;

    @Column("retry_count")
    private Integer retryCount;

}
