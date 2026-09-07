package com.motcs;

import com.motcs.core.document.DocumentResponse;
import com.motcs.core.document.DocumentUploadRequest;
import com.motcs.core.knowledge.graph.GraphRagService;
import com.motcs.core.knowledge.graph.GraphRagRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文档上传 + GraphRAG 多跳查询 + 对话模型总结 集成测试
 * <p>
 * 流程：上传文档 → 向量检索 → Neo4j 多跳图谱召回 → 合并上下文 → 智谱 glm-4-flash 系统性总结
 * <p>
 * 前置条件：Neo4j 数据库已启动（bolt://127.0.0.1:7687）
 */
@SpringBootTest
class DocumentVectorSearchTest {

    private static final String TEST_FILE_PATH = "C:\\Users\\Administrator\\Desktop\\12345.txt";
    /**
     * 文档业务编码：同编码重复上传为更新语义
     */
    private static final String TEST_DOC_CODE = "harbin-wuhuashan-guide";
    /**
     * 测试租户编码
     */
    private static final String TEST_TENANT_CODE = "test-tenant";
    /**
     * 测试系统类型
     */
    private static final String TEST_SYSTEM_TYPE = "travel-guide";

    @Autowired
    private GraphRagService graphRagService;

    @Test
    void testUploadAndGraphRagQuery() throws IOException, InterruptedException {
        // ========== 1. 读取本地文件 ==========
        Path path = Path.of(TEST_FILE_PATH);
        assertTrue(Files.exists(path), "测试文件不存在: " + TEST_FILE_PATH);

        byte[] fileBytes = Files.readAllBytes(path);
        String fileName = path.getFileName().toString();

        MultipartFile multipartFile = new MockMultipartFile(
                "file",
                fileName,
                "text/plain",
                fileBytes
        );

        // ========== 2. 构建上传请求并入库（指定 docCode，触发图谱构建） ==========
        DocumentUploadRequest request = DocumentUploadRequest.builder()
                .file(multipartFile)
                .fileName(fileName)
                .title("哈尔滨国庆小兴安岭五花山7天自驾路书")
                .description("国庆自驾路线攻略")
                .documentType("TEXT")
                .docCode(TEST_DOC_CODE)
                .tenantCode(TEST_TENANT_CODE)
                .systemType(TEST_SYSTEM_TYPE)
                .build();

        DocumentResponse response = graphRagService.insertKnowledgeDoc(request).block();

        System.out.println("\n========== 上传结果 ==========");
        if (ObjectUtils.isEmpty(response)) {
            fail("分片数量应大于0");
        }
        System.out.println("状态: " + response.getStatus());
        System.out.println("文档ID: " + response.getDocumentId());
        System.out.println("业务编码: " + response.getDocCode());
        System.out.println("文件名: " + response.getFileName());
        System.out.println("分片数量: " + response.getChunkCount());
        System.out.println("错误信息: " + response.getErrorMessage());

        assertEquals("SUCCESS", response.getStatus(),
                "文档上传失败: " + response.getErrorMessage());
        assertNotNull(response.getChunkCount(), "分片数量不应为空");
        assertTrue(response.getChunkCount() > 0, "分片数量应大于0");

        // 等待向量索引建立 + 图谱关系构建完成
        Thread.sleep(3000);

        // ========== 3. GraphRAG 多跳查询 + 对话模型总结 ==========
        String userQuestion = "五花山最佳观赏期是什么时候";

        System.out.println("\n========== GraphRAG 多跳查询 ==========");
        System.out.println("用户问题: " + userQuestion);
        System.out.println("正在执行：向量检索 → 多跳图谱召回 → 合并上下文 → AI 总结...\n");
        GraphRagRequest ragQuery = GraphRagRequest.builder()
                .question(userQuestion).tenantCode(TEST_TENANT_CODE)
                .systemType(TEST_SYSTEM_TYPE).build();
        String answer = graphRagService.graphRagQuery(ragQuery)
                .blockOptional().orElse("没有回答内容！");

        System.out.println("========== AI 系统性总结回答 ==========");
        System.out.println(answer);
        System.out.println("\n========== 测试完成 ==========");

        assertNotNull(answer, "AI回答不应为空");
        assertFalse(answer.isBlank(), "AI回答不应为空白");
    }

    @Test
    void testUploadAndGraphRagQuery2() {
        // ========== 3. GraphRAG 多跳查询 + 对话模型总结 ==========
        String userQuestion = "五花山最佳观赏期是什么时候，只旅游5天，怎么安排？";

        System.out.println("\n========== GraphRAG 多跳查询 ==========");
        System.out.println("用户问题: " + userQuestion);
        System.out.println("正在执行：向量检索 → 多跳图谱召回 → 合并上下文 → AI 总结...\n");

        GraphRagRequest ragQuery = GraphRagRequest.builder()
                .question(userQuestion).tenantCode(TEST_TENANT_CODE)
                .systemType(TEST_SYSTEM_TYPE).build();
        String answer = graphRagService.graphRagQuery(ragQuery)
                .blockOptional().orElse("没有回答内容！");

        System.out.println("========== AI 系统性总结回答 ==========");
        System.out.println(answer);
        System.out.println("\n========== 测试完成 ==========");

        assertNotNull(answer, "AI回答不应为空");
        assertFalse(answer.isBlank(), "AI回答不应为空白");
    }

}
