package com.motcs.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.motcs.commons.FileUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业级文档切分器（适配多跳 RAG）
 * <p>
 * 核心特性：
 * 1. 结构优先：按 Markdown 标题层级（# / ## / ###）切分，不破坏语义单元
 * 2. 双层架构：粗粒度推理切片（1024~1536 token）+ 细粒度检索切片（384~512 token）
 * 3. 语义边界：禁止截断完整句子，向后顺延至句号/分号/问号/感叹号
 * 4. 固定重叠：细粒度 25%，粗粒度 20%
 * 5. 标题全绑定：每个切片前置完整层级标题链路
 * 6. 页码追踪：识别 [[PAGE:n]] 标记
 * 7. 关联组网：parentChunkId / prevChunkId / nextChunkId
 */
@Log4j2
@Component
public class EnterpriseChunker {

    private static final int FINE_TARGET = 448;    // 细粒度目标 token（384~512 中值）
    private static final int FINE_MAX = 512;
    private static final int COARSE_TARGET = 1280; // 粗粒度目标 token（1024~1536 中值）
    private static final int COARSE_MAX = 1536;
    private static final double FINE_OVERLAP = 0.25;
    private static final double COARSE_OVERLAP = 0.20;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern PAGE_MARKER = Pattern.compile("\\[\\[PAGE:(\\d+)]]");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；!?;]");

    private final Encoding encoding;

    public EnterpriseChunker() {
        this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * 切分结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chunk {
        private String chunkId;
        private String parentChunkId;   // 粗粒度父切片ID（细粒度有值）
        private String prevChunkId;     // 上一相邻切片ID
        private String nextChunkId;     // 下一相邻切片ID
        private String content;         // 切片正文（含标题前缀）
        private String rawContent;      // 切片原始正文（不含标题前缀）
        private List<String> titleHierarchy; // 层级标题链路
        private String chunkType;       // FINE / COARSE
        private int tokenSize;
        private String pageNumber;      // 页码，跨页用范围如 "1-2"
        private int offsetStart;
        private int offsetEnd;
    }

    /**
     * 企业级切分入口
     *
     * @param markdownContent 含 [[PAGE:n]] 标记的 Markdown 文本
     * @return 所有切片（细粒度在前，粗粒度在后）
     */
    public List<Chunk> chunk(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return List.of();
        }

        // 1. 解析文档结构：按标题分节
        List<Section> sections = parseSections(markdownContent);
        log.info("文档结构解析完成，共 {} 个章节", sections.size());

        List<Chunk> allFineChunks = new ArrayList<>();
        List<Chunk> allCoarseChunks = new ArrayList<>();

        // 2. 每个章节生成粗粒度切片
        for (Section section : sections) {
            List<Chunk> coarseChunks = splitCoarse(section);
            allCoarseChunks.addAll(coarseChunks);
        }

        // 3. 每个粗粒度切片拆分为细粒度切片
        for (Chunk coarse : allCoarseChunks) {
            List<Chunk> fineChunks = splitFine(coarse);
            allFineChunks.addAll(fineChunks);
        }

        // 4. 建立细粒度切片的 prev/next 关联
        linkChunks(allFineChunks);
        linkChunks(allCoarseChunks);

        log.info("企业级切分完成：细粒度 {} 片，粗粒度 {} 片",
                allFineChunks.size(), allCoarseChunks.size());

        // 细粒度在前（用于向量检索），粗粒度在后（用于推理补全）
        List<Chunk> result = new ArrayList<>(allFineChunks);
        result.addAll(allCoarseChunks);
        return result;
    }

    // ==================== 内部实现 ====================

    /**
     * 文档章节
     */
    private record Section(List<String> titleHierarchy, String content, int startOffset, String pageNumber) {
    }

    /**
     * 按 Markdown 标题解析章节
     */
    private List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        List<String> currentHierarchy = new ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        int currentStart = 0;
        int currentPage = 1;
        boolean hasContent = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 页码标记保留在内容中，后续切分时按切片 内容提取对应页码
            Matcher pageMatcher = PAGE_MARKER.matcher(line);
            if (pageMatcher.find()) {
                currentPage = Integer.parseInt(pageMatcher.group(1));
                currentContent.append(line).append("\n");
                hasContent = true;
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(line.trim());
            if (headingMatcher.matches()) {
                // 保存上一节
                if (hasContent && !currentContent.isEmpty()) {
                    sections.add(new Section(new ArrayList<>(currentHierarchy),
                            currentContent.toString().trim(), currentStart, String.valueOf(currentPage)));
                }
                // 更新标题层级
                int level = headingMatcher.group(1).length();
                String title = headingMatcher.group(2).trim();
                while (currentHierarchy.size() >= level) {
                    currentHierarchy.removeLast();
                }
                while (currentHierarchy.size() < level - 1) {
                    currentHierarchy.add("");
                }
                if (currentHierarchy.size() == level - 1) {
                    currentHierarchy.add(title);
                } else {
                    currentHierarchy.set(level - 1, title);
                }
                currentContent = new StringBuilder();
                currentStart = i;
                hasContent = false;
            } else {
                if (!line.trim().isEmpty()) {
                    hasContent = true;
                }
                currentContent.append(line).append("\n");
            }
        }
        // 最后一节
        if (hasContent && !currentContent.isEmpty()) {
            sections.add(new Section(new ArrayList<>(currentHierarchy),
                    currentContent.toString().trim(), currentStart, String.valueOf(currentPage)));
        }

        // 无标题的纯文本：作为单个章节
        if (sections.isEmpty()) {
            sections.add(new Section(List.of(), text.trim(), 0, "1"));
        }
        return sections;
    }

    /**
     * 粗粒度切分：按语义边界，1024~1536 token，20% 重叠
     */
    private List<Chunk> splitCoarse(Section section) {
        String titlePrefix = buildTitlePrefix(section.titleHierarchy());
        String content = section.content();
        int baseTokens = countTokens(titlePrefix);
        int contentBudget = COARSE_TARGET - baseTokens;

        List<String> segments = splitBySemanticBoundary(content, contentBudget,
                (int) (contentBudget * COARSE_OVERLAP), COARSE_MAX);

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            String pageRange = FileUtils.extractPageRange(seg);
            String cleanSeg = FileUtils.stripPageMarkers(seg);
            String fullContent = titlePrefix + cleanSeg;
            chunks.add(Chunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .content(fullContent)
                    .rawContent(seg)  // 保留页码标记，供细切片提取页码范围
                    .titleHierarchy(new ArrayList<>(section.titleHierarchy()))
                    .chunkType("COARSE")
                    .tokenSize(countTokens(fullContent))
                    .pageNumber(pageRange)
                    .offsetStart(section.startOffset() + i)
                    .offsetEnd(section.startOffset() + i + seg.length())
                    .build());
        }
        return chunks;
    }

    /**
     * 细粒度切分：基于粗粒度切片拆分，384~512 token，25% 重叠
     */
    private List<Chunk> splitFine(Chunk coarse) {
        String titlePrefix = buildTitlePrefix(coarse.getTitleHierarchy());
        String content = coarse.getRawContent();
        int baseTokens = countTokens(titlePrefix);
        int contentBudget = FINE_TARGET - baseTokens;

        List<String> segments = splitBySemanticBoundary(content, contentBudget,
                (int) (contentBudget * FINE_OVERLAP), FINE_MAX);

        List<Chunk> chunks = new ArrayList<>();
        for (String seg : segments) {
            String pageRange = FileUtils.extractPageRange(seg);
            String cleanSeg = FileUtils.stripPageMarkers(seg);
            String fullContent = titlePrefix + cleanSeg;
            chunks.add(Chunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .parentChunkId(coarse.getChunkId())
                    .content(fullContent)
                    .rawContent(cleanSeg)
                    .titleHierarchy(new ArrayList<>(coarse.getTitleHierarchy()))
                    .chunkType("FINE")
                    .tokenSize(countTokens(fullContent))
                    .pageNumber(pageRange)
                    .offsetStart(coarse.getOffsetStart())
                    .offsetEnd(coarse.getOffsetEnd())
                    .build());
        }
        return chunks;
    }

    /**
     * 按语义边界切分文本：优先在句子结束符处切分，不截断完整句子
     *
     * @param text     原始文本
     * @param target   目标长度（token）
     * @param overlap  重叠长度（token）
     * @param maxLimit 最大长度（token），超过则强制切分
     * @return 切分后的文本段列表
     */
    private List<String> splitBySemanticBoundary(String text, int target, int overlap, int maxLimit) {
        if (text == null || text.isBlank()) return List.of();

        List<String> result = new ArrayList<>();
        int charPos = 0;
        int textLen = text.length();

        while (true) {
            // 估算目标字符位置（token ≈ chars * 0.6，中文约 1 token/1.5 char）
            int estimatedEnd = charPos + estimateCharsForTokens(text, charPos, target);
            if (estimatedEnd >= textLen) {
                String seg = text.substring(charPos).trim();
                if (!seg.isEmpty()) result.add(seg);
                break;
            }

            // 向后查找最近的句子结束符
            int cutPoint = findSentenceEnd(text, estimatedEnd, Math.min(estimatedEnd + 200, textLen));
            if (cutPoint <= charPos) {
                // 找不到句子边界，强制按最大长度切分
                cutPoint = Math.min(charPos + estimateCharsForTokens(text, charPos, maxLimit), textLen);
            }

            String segment = text.substring(charPos, cutPoint).trim();
            if (!segment.isEmpty()) {
                result.add(segment);
            }

            // 计算重叠起始位置
            if (overlap > 0 && cutPoint < textLen) {
                int overlapChars = estimateCharsForTokens(text, cutPoint - 1, overlap);
                charPos = Math.max(cutPoint - overlapChars, charPos + 1);
            } else {
                charPos = cutPoint;
            }

            // 防止死循环
            if (charPos >= textLen) break;
        }
        return result;
    }

    /**
     * 在 [start, end] 范围内从 start 向后查找最近的句子结束符位置
     */
    private int findSentenceEnd(String text, int start, int end) {
        Matcher m = SENTENCE_END.matcher(text);
        int pos = start;
        while (m.find(pos)) {
            if (m.start() >= end) break;
            pos = m.start() + 1;
        }
        return pos > start ? pos : -1;
    }

    /**
     * 估算从 start 位置开始取 targetTokens 个 token 对应的字符数
     */
    private int estimateCharsForTokens(String text, int start, int targetTokens) {
        if (targetTokens <= 0) return 0;
        int end = Math.min(start + targetTokens * 3, text.length());
        String sample = text.substring(start, end);
        int sampleTokens = countTokens(sample);
        if (sampleTokens == 0) return targetTokens * 2;
        // 按比例估算，留 10% 余量
        return (int) ((double) sample.length() * targetTokens / sampleTokens * 0.9);
    }

    /**
     * 构建标题前缀：[一级标题] > [二级标题] > [三级标题]\n\n
     */
    private String buildTitlePrefix(List<String> hierarchy) {
        if (hierarchy == null || hierarchy.isEmpty()) return "";
        String path = hierarchy.stream()
                .filter(h -> h != null && !h.isBlank())
                .reduce((a, b) -> a + " > " + b)
                .orElse("");
        return path.isEmpty() ? "" : "[" + path + "]\n\n";
    }

    /**
     * 建立切片的 prev/next 关联
     */
    private void linkChunks(List<Chunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) chunks.get(i).setPrevChunkId(chunks.get(i - 1).getChunkId());
            if (i < chunks.size() - 1) chunks.get(i).setNextChunkId(chunks.get(i + 1).getChunkId());
        }
    }

    private int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }
}
