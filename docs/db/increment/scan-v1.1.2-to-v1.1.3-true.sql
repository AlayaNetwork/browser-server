USE `scan_platon`;
ALTER TABLE network_stat add COLUMN `year_num` int(11) DEFAULT 1 COMMENT '年份';
ALTER TABLE slash add COLUMN `is_handle` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已处理，1为是，0为否，默认是0';
ALTER TABLE token_inventory add COLUMN `retry_count` int(1) DEFAULT 1 COMMENT '重试次数，最多重试5次';