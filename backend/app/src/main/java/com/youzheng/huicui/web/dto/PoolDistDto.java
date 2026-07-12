package com.youzheng.huicui.web.dto;

/**
 * 批次案件状态分布（契约 PoolDist·v1.17.0）。批次运营表的迷你分布条数据源。
 * s0=待派单(平台公海) s1=待接单 s2=服务商公海 s3=私海进行中 s4=开放抢单池；
 * settled=已结清；closed=撤案/坏账/作废 终态。
 */
public record PoolDistDto(int s0, int s1, int s2, int s3, int s4, int settled, int closed) {}
