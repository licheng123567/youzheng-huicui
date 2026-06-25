package com.youzheng.huicui.web.dto;

/**
 * 契约 CoordinatorRef（协调员引用 PC↔项目/批次 多对多 BR-M2-13）。
 * 由 batch_coordinators JOIN account 填充（id=account.id, name=account.name）。
 */
public record BatchCoordinatorRef(String id, String name) {}
