package com.armada.task.service;

import com.armada.task.model.dto.PlanRow;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinResultStatus;

import java.util.ArrayList;
import java.util.List;

/** 计划行生成纯函数:无效链接→FAILED 行,有效链接按分配方式→PENDING 行。无 Spring/DB,可单测。 */
public final class PlanRowGenerator {

    private PlanRowGenerator() {
    }

    /**
     * 生成账号×链接计划行。
     *
     * @param mode                 分配方式(DistributionMode 常量)
     * @param accounts             选中账号(顺序即轮询顺序)
     * @param validLinks           有效群链接(去重保序)
     * @param invalidLinks         无效行(各转一条 FAILED)
     * @param accountsPerLink      方式一:每链接账号数
     * @param executorAccountCount 方式二:参与账号数
     * @param linksPerAccount      方式二:每账号链接数
     * @return 计划行(先 FAILED 后 PENDING)
     */
    public static List<PlanRow> generate(String mode, List<SelectedAccount> accounts,
            List<String> validLinks, List<String> invalidLinks,
            int accountsPerLink, int executorAccountCount, int linksPerAccount) {
        List<PlanRow> rows = new ArrayList<>();
        for (String link : invalidLinks) {
            rows.add(new PlanRow(null, "", link, JoinResultStatus.FAILED, "非群链接"));
        }
        int n = accounts.size();
        if (DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(mode)) {
            int accountCount = Math.max(executorAccountCount, 0);
            int linkCap = Math.min(Math.max(linksPerAccount, 0), validLinks.size());
            for (int a = 0; a < accountCount; a++) {
                SelectedAccount acc = n == 0 ? null : accounts.get(a % n);
                for (int l = 0; l < linkCap; l++) {
                    rows.add(pending(acc, validLinks.get(l)));
                }
            }
        } else {
            int perLink = Math.max(accountsPerLink, 0);
            int rr = 0;
            for (String link : validLinks) {
                for (int i = 0; i < perLink; i++) {
                    SelectedAccount acc = n == 0 ? null : accounts.get(rr % n);
                    rr++;
                    rows.add(pending(acc, link));
                }
            }
        }
        return rows;
    }

    private static PlanRow pending(SelectedAccount acc, String link) {
        return new PlanRow(acc == null ? null : acc.accountId(),
                acc == null ? "" : acc.phone(), link, JoinResultStatus.PENDING, "");
    }
}
