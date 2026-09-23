package com.zzp.agent.document.split;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档拆分服务：按完整句子切块。
 *
 * <p>规则：
 * <ul>
 *   <li>以句末标点（。！？…；西文句点仅后随空白/行尾）为句子边界;</li>
 *   <li>按句累积成块,当追加下一句会超过 {@link #MAX_CHUNK_LEN} 时,在上一句句号处断块——绝不从句子中间切断;</li>
 *   <li>单个句子自己超过上限时,才在句内按次级标点/硬切兜底;</li>
 *   <li>相邻块之间保留 {@link #OVERLAP} 字符重叠,防止上下文被硬切断。</li>
 * </ul>
 */
@Service
public class ChunkingService {

    /** 单块字符数上限 */
    private static final int MAX_CHUNK_LEN = 800;

    /** 相邻块重叠字符数 */
    private static final int OVERLAP = 50;

    /** 句末标点：问/叹/省略点,以及(前后非字母数字)西文句点后接空白或行尾 */
    private static final Pattern SENTENCE_END =
            Pattern.compile("[。！？…；]|(?<![0-9A-Za-z])\\.(?=[\\s\\n]|$)");

    /** 句中次级断点（单超长句的兜底） */
    private static final Pattern INNER_BREAK = Pattern.compile("[，、：；,]");

    public List<TextChunk> chunk(String documentId, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String docId = documentId == null ? "" : documentId;
        List<String> sentences = splitSentences(text);
        List<TextChunk> result = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int curLen = 0;
        int seq = 0;

        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 若当前块加上这句会超限，先落块（边界恰在句号，不切断句子）
            if (curLen > 0 && curLen + 1 + s.length() > MAX_CHUNK_LEN) {
                seq = addChunk(result, buf, curLen, docId, seq);
                buf.clear();
                curLen = 0;
            }
            // 单句超长：句内兜底，直接成块
            if (s.length() > MAX_CHUNK_LEN) {
                if (curLen > 0) {
                    seq = addChunk(result, buf, curLen, docId, seq);
                    buf.clear();
                    curLen = 0;
                }
                for (String piece : splitLongSentence(s)) {
                    buf.add(piece);
                    seq = addChunk(result, buf, piece.length(), docId, seq);
                    buf.clear();
                }
                curLen = 0;
                continue;
            }
            buf.add(s);
            curLen += (buf.size() > 1 ? 1 : 0) + s.length();
        }
        if (curLen > 0) {
            addChunk(result, buf, curLen, docId, seq);
        }
        return result;
    }

    /**
     * 把一段句子列表组装成一个 TextChunk 加入结果：编号为 seq，chunkId 为 documentId_seq。
     * 返回下一个可用块序号（当前块用掉的 seq + 1）。
     */
    private int addChunk(List<TextChunk> result, List<String> buf, int len,
                         String documentId, int seq) {
        if (buf.isEmpty()) {
            return seq;
        }
        String joined = String.join("\n", buf).trim();
        if (joined.isEmpty()) {
            return seq;
        }
        result.add(new TextChunk(
                documentId + "_" + seq, documentId, seq, "", joined, joined.length()));
        return seq + 1;
    }

    /** 按句末标点把文本切成句子列表。 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(text);
        int start = 0;
        while (true) {
            int end = text.length();
            if (m.find()) {
                end = m.end();
            }
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            if (end >= text.length()) {
                break;
            }
            start = end;
        }
        return sentences;
    }

    /**
     * 单超长句兜底：先按句中次级标点（，、：；）分段；若某段长度仍 > MAX_CHUNK_LEN，
     * 再按固定步长硬切。硬切保证 start 单调前进，尾部不足一步时作为末块收尾。
     */
    private List<String> splitLongSentence(String s) {
        List<String> pieces = new ArrayList<>();
        Matcher m = INNER_BREAK.matcher(s);
        StringBuilder cur = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            cur.append(s, lastEnd, m.end());
            if (cur.length() >= MAX_CHUNK_LEN - OVERLAP) {
                pieces.add(cur.toString());
                cur.setLength(0);
            }
            lastEnd = m.end();
        }
        if (lastEnd < s.length()) {
            cur.append(s, lastEnd, s.length());
        }

        int step = MAX_CHUNK_LEN - OVERLAP;
        // 兜底：把尚未成块的正文硬切。start 单调 +step，只剩不足 step 时一次收尾。
        String rest = cur.toString();
        if (rest.length() > MAX_CHUNK_LEN) {
            int start = 0;
            while (start < rest.length()) {
                int end = Math.min(start + step, rest.length());
                pieces.add(rest.substring(start, end));
                if (end == rest.length()) {
                    break;                 // 已到末尾，收尾结束
                }
                start = end - OVERLAP;     // 与下一块 overlap 衔接，且严格 < end
            }
        } else if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }
}