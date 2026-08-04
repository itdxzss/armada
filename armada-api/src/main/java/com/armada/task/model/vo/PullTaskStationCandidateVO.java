package com.armada.task.model.vo;

/** 补充站台页中的一个在线正常且尚未用于当前群的候选账号。 */
public record PullTaskStationCandidateVO(long accountId, String accountPhone) {
}
