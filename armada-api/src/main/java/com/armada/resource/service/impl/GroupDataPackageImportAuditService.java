package com.armada.resource.service.impl;

import com.armada.resource.mapper.GroupDataPackageImportMapper;
import com.armada.resource.converter.GroupDataPackageConverter;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.entity.GroupDataPackageImport;
import com.armada.resource.model.vo.GroupDataPackageImportVO;
import com.armada.shared.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 导入审计先独立记录；号码写入回滚后仍保留可追溯失败。 */
@Service
public class GroupDataPackageImportAuditService {
    /** 导入生命周期。 */
    static final int PROCESSING = 1, SUCCESS = 2, FAILED = 3;
    private final GroupDataPackageImportMapper imports;
    private final GroupDataPackageConverter converter;
    /** 注入真实审计访问。 */
    public GroupDataPackageImportAuditService(GroupDataPackageImportMapper imports,
            GroupDataPackageConverter converter) { this.imports = imports; this.converter = converter; }

    /** 在独立事务记录导入开始。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GroupDataPackageImport begin(GroupDataPackageImport row) {
        row.setStatus(PROCESSING); row.setTotalRows(0); row.setAcceptedRows(0);
        row.setInvalidRows(0); row.setDuplicatedRows(0); row.setPrivacyFilteredRows(0);
        imports.insert(row); return row;
    }
    /** 加入号码写事务，成功及号码原子提交。 */
    public void succeed(GroupDataPackageImport row) { row.setStatus(SUCCESS); imports.finish(row); }
    /** 号码写事务已回滚，单独持久化失败结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(GroupDataPackageImport row, String reason) {
        row.setStatus(FAILED); row.setFinishedAt(System.currentTimeMillis());
        row.setFailureReason(reason.substring(0, Math.min(reason.length(), 512)));
        imports.finish(row);
    }
    /** 按包分页读取导入审计。调用方已校验包可访问。 */
    public PageResult<GroupDataPackageImportVO> list(long id, GroupDataPackageQuery query) {
        return PageResult.of(imports.page(id, query).stream().map(converter::imported).toList(),
                query.getPage(), query.getPageSize(), imports.count(id));
    }
}
