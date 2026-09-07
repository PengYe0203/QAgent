package com.company.qagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化检索所需的数据库扩展与索引。
 *
 * <p>pgvector 自动配置会建 vector_store 表和向量索引；
 * 但混合检索的额外需求（中文友好的 trgm 扩展、全文检索索引）需要这里补建。</p>
 */
@Component
public class VectorStoreInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 1. 安装 pg_trgm 扩展（三元组模糊匹配，对中文友好）
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            log.info("已启用 pg_trgm 扩展");

            // 2. 全文检索索引：对 metadata 里的中文分词列 content_zh 建 tsvector GIN 索引
            //    content_zh 是入库时用 HanLP 分词、空格分隔的文本（如"员工 报销 制度"），
            //    simple 配置按空格切分，能正确处理中文词
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_vector_store_tsv
                    ON vector_store USING GIN (to_tsvector('simple', metadata->>'content_zh'))
                    """);
            log.info("已创建全文检索索引 idx_vector_store_tsv (content_zh)");

            // 3. trgm 相似度索引（中文模糊匹配）
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_vector_store_trgm
                    ON vector_store USING GIN (content gin_trgm_ops)
                    """);
            log.info("已创建 trgm 索引 idx_vector_store_trgm");
        } catch (Exception e) {
            // 不阻塞启动，但记录警告（可能 vector_store 表尚未创建）
            log.warn("初始化检索索引失败（keyword/trgm 检索可能不可用）: {}", e.getMessage());
        }
    }
}
