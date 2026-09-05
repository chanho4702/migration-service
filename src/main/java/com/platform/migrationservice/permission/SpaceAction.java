package com.platform.migrationservice.permission;

/** 계층은 org-service가 판정한다: VIEW &lt; COMMENT &lt; EDIT &lt; ADMIN. 이관은 ADMIN만 쓴다. */
public enum SpaceAction { VIEW, COMMENT, EDIT, ADMIN }
