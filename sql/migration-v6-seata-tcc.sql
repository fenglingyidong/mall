CREATE TABLE IF NOT EXISTS undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info',
    log_status INT NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
);

CREATE TABLE IF NOT EXISTS tcc_fence_log (
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    action_name VARCHAR(64) NOT NULL COMMENT 'tcc action name',
    status TINYINT NOT NULL COMMENT 'tried:1; committed:2; rollbacked:3; suspended:4',
    gmt_create DATETIME(3) NOT NULL COMMENT 'create time',
    gmt_modified DATETIME(3) NOT NULL COMMENT 'update time',
    PRIMARY KEY (xid, branch_id),
    KEY idx_gmt_modified (gmt_modified),
    KEY idx_status (status)
);
