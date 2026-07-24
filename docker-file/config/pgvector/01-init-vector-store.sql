-- DataAgent PGVector 初始化基线。
-- 该脚本由 PostgreSQL 容器在全新数据目录首次启动时执行，不是应用启动期迁移器。
-- 维度、Schema 和表名必须与 application.yml 中的 PGVector 配置保持一致。

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

-- Spring AI 检索会同时使用 HNSW 和 metadata jsonpath 过滤。
-- pgvector 0.8+ 的严格顺序迭代扫描可以在过滤条件较强时继续扫描候选，降低召回不足风险。
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET hnsw.iterative_scan = %L', current_database(), 'strict_order');
END
$$;

CREATE TABLE IF NOT EXISTS public.cares_data_agent_weihai (
    -- 使用 text 是因为指标文档 ID 为 metric:<generation>:<metricKey>，不是 UUID。
    id text PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(1024)
);

-- 纯向量检索使用余弦距离；HNSW 适合轻量在线查询，构建和写入成本高于无索引模式。
CREATE INDEX IF NOT EXISTS cares_data_agent_weihai_embedding_hnsw_idx
    ON public.cares_data_agent_weihai
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Spring AI PGVector 使用 metadata::jsonb @@ jsonpath 执行 Agent、类型和 generation 过滤。
CREATE INDEX IF NOT EXISTS cares_data_agent_weihai_metadata_gin_idx
    ON public.cares_data_agent_weihai
    USING gin ((metadata::jsonb) jsonb_path_ops);

COMMIT;
