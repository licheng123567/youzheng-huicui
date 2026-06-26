    76	        pageArgs.add(pg.offset);
    77	        // FAILED 行不剔除（失败不退条数 BR-M9-08），failure_reason 随行返回。
    78	        List<SmsSendRecordDto> items = jdbc.query(
    79	                "SELECT id, case_id, project_id, template, status, failure_reason, sent_at"
    80	                        + " FROM sms_record" + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
    81	                SMS_MAPPER, pageArgs.toArray());
    82	
    83	        return Page.of(items, pg, total == null ? 0 : total);
    84	    }
    85	
    86	    // ── [16b] GET /sms-records/export ─────────────────────────────────────────────
    87	    // x-data-scope=range，无 x-permission。同 listSmsRecords 同 scope 同过滤参数。
    88	    // 读端点·靠 scope 裁剪；返回 { url } 导出文件占位（文件通道 TBD，地基期恒 null）。
    89	    @GetMapping("/sms-records/export")
    90	    public java.util.Map<String, Object> exportSmsRecords(
    91	            @RequestParam(required = false) String projectId,
    92	            @RequestParam(required = false) String caseId,
    93	            @RequestParam(required = false) String status,
    94	            @RequestParam(required = false) String from,
    95	            @RequestParam(required = false) String to) {
    96	        CurrentSubject s = SubjectContext.get();
    97	        // 过滤/scope 与 list 同口径校验（非法过滤值同样 422）；range 裁剪不串组织。
    98	        List<Object> args = new ArrayList<>();
    99	        buildWhere(s, projectId, caseId, status, from, to, args);
   100	
   101	        // 文件打包通道 TBD → url 占位 null（对齐契约 exportSmsRecords 响应 { url: string|null }）。
   102	        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
   103	        resp.put("url", null);
   104	        return resp;
   105	    }
   106	
   107	    // ── helpers ─────────────────────────────────────────────────────────────────
   108	
   109	    /**
   110	     * 构造 list/export 共用的 WHERE（过滤 + range scope）；非法过滤值 → 422（绝不 5xx）。
