package com.armada.group.service;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.read.listener.ReadListener;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把手填 text 与上传文件(TXT/CSV/Excel)统一抽成行列表。
 *
 * <ul>
 *   <li>TXT: 纯文本逐行,trim 跳空。</li>
 *   <li>CSV/XLS/XLSX: 走 FastExcel 取首列(第 0 列),trim 跳空。</li>
 *   <li>text 与 file 合并:text 行在前,file 行在后。</li>
 *   <li>二者都为空/null → 返回空列表。</li>
 *   <li>不支持的文件扩展名 → 抛 {@link BusinessException}。</li>
 * </ul>
 */
@Component
public class FileLinesExtractor {

    /**
     * 提取行列表。
     *
     * @param file 上传文件(TXT/CSV/Excel),可为 null 或空文件
     * @param text 手填多行文本,可为 null 或空白
     * @return 合并去空后的行列表(不保证去重,由调用方 LineImporter 去重)
     * @throws BusinessException 文件格式不受支持或读取失败
     */
    public List<String> extract(MultipartFile file, String text) {
        List<String> lines = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (String l : text.split("\\R", -1)) {
                if (!l.isBlank()) {
                    lines.add(l.trim());
                }
            }
        }
        if (file != null && !file.isEmpty()) {
            lines.addAll(parseFile(file));
        }
        return lines;
    }

    private List<String> parseFile(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".txt")) {
                return parseTxt(file);
            }
            if (name.endsWith(".csv") || name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readFirstColumn(file);
            }
            throw new BusinessException(ErrorCode.VALIDATION, "仅支持 TXT/CSV/Excel(.xlsx/.xls) 文件");
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件读取失败: " + e.getMessage());
        }
    }

    private List<String> parseTxt(MultipartFile file) throws IOException {
        List<String> out = new ArrayList<>();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        for (String l : content.split("\\R", -1)) {
            if (!l.isBlank()) {
                out.add(l.trim());
            }
        }
        return out;
    }

    private List<String> readFirstColumn(MultipartFile file) throws IOException {
        List<String> out = new ArrayList<>();
        FastExcel.read(file.getInputStream(), new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext ctx) {
                String first = row.get(0);
                if (first != null && !first.isBlank()) {
                    out.add(first.trim());
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext ctx) {
                // 解析完成后无需额外操作
            }
        }).sheet().headRowNumber(0).doRead();
        return out;
    }
}
