package com.armada.contact.task.service;

import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;

import java.util.Optional;

/**
 * 通讯录营销任务状态机。
 *
 * <p>纯函数，不碰数据库。迁移规则与竞品一致：已停止和已完成都是终态，
 * 「停止后任务将被终止，且无法恢复」是竞品确认弹框的原文。</p>
 */
public final class ContactTaskStateMachine {

    private ContactTaskStateMachine() {
    }

    /**
     * 计算一次动作后的目标状态。
     *
     * @param current 当前运行状态
     * @param action 请求动作
     * @return 允许迁移时返回目标状态，否则返回空
     */
    public static Optional<ContactTaskRunStatus> next(
            ContactTaskRunStatus current, ContactTaskAction action) {
        if (current == null || action == null) {
            return Optional.empty();
        }
        return switch (current) {
            case NOT_STARTED -> action == ContactTaskAction.START
                    ? Optional.of(ContactTaskRunStatus.RUNNING)
                    : Optional.empty();
            case RUNNING -> switch (action) {
                case PAUSE -> Optional.of(ContactTaskRunStatus.PAUSED);
                case STOP -> Optional.of(ContactTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            case PAUSED -> switch (action) {
                case RESUME -> Optional.of(ContactTaskRunStatus.RUNNING);
                case STOP -> Optional.of(ContactTaskRunStatus.STOPPED);
                default -> Optional.empty();
            };
            // 已完成与已停止都是终态，任何动作都不接受
            case COMPLETED, STOPPED -> Optional.empty();
        };
    }

    /**
     * 判断任务当前是否允许编辑。
     *
     * <p>竞品口径：只有未开始的任务可编辑，一旦开始就只能查看。</p>
     *
     * @param current 当前运行状态
     * @return 可编辑则 true
     */
    public static boolean isEditable(ContactTaskRunStatus current) {
        return current == ContactTaskRunStatus.NOT_STARTED;
    }
}
