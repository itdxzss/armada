package com.armada.hyperlink.click.service;

import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisExportDTO;
import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisQuery;
import com.armada.hyperlink.click.model.enums.HyperlinkClickAnalysisMode;
import com.armada.hyperlink.click.model.vo.HyperlinkClickAnalysisVO;
import com.armada.hyperlink.data.model.vo.DataPackageExportFile;

/** 超链任务按收件人号码聚合的点击分析合同。 */
public interface HyperlinkClickAnalysisService {

    /** 按时间、国家、分组维度和多档阈值分析。 */
    HyperlinkClickAnalysisVO analyze(
            HyperlinkClickAnalysisMode mode, HyperlinkClickAnalysisQuery query);

    /** 导出一个阈值命中的号码 TXT。 */
    DataPackageExportFile export(
            HyperlinkClickAnalysisMode mode, HyperlinkClickAnalysisExportDTO request);
}
