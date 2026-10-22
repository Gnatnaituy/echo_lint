#!/usr/bin/env python3
"""EchoLint MySQL 8 -> PostgreSQL 17 数据迁移（只搬数据；schema 由新版应用建好）。

为什么不直接上 pgloader：pgloader 自带的 QMynd 客户端不支持 MySQL 8.x 默认的
``caching_sha2_password``，连接会报 ``MYSQL-UNSUPPORTED-AUTHENTICATION``。
本脚本用 pymysql（支持 caching_sha2）读、psycopg2 参数化写，按列名对齐、按类型转换。

适用流程（与 docs/postgres-migration.md 一致）：
    1. 新版应用先在空 PG 上建 schema（注意：新空库首次启动需要先关一次 Flyway，
       见 docs/postgres-migration.md「实战补充」）；
    2. ``python3 tools/migrate_mysql_to_pg.py --dry-run`` 检查表/列映射与源行数；
    3. ``python3 tools/migrate_mysql_to_pg.py --apply`` 清空目标业务表、搬数据、重置序列、校验行数；
    4. 重启应用，按手册继续做聚合/时间/向量校验。

连接参数（优先级：命令行 > 环境变量 > --env-file 中的同名变量）：
    MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_DB/MYSQL_PASSWORD
    PG_HOST/PG_PORT/PG_USER/PG_DB/PG_PASSWORD

默认值（docker compose 的宿主端口）：
    MySQL 127.0.0.1:3306（旧栈若把 MySQL 映射到 3307 请显式指定）
    PostgreSQL 127.0.0.1:5433（compose 把容器 5432 映射到宿主 5433）

安全：只读 MySQL；PostgreSQL 只写业务表，不动 flyway_schema_history / knowledge_chunks。
"""
import argparse
import datetime
import decimal
import os
import sys

import pymysql
import psycopg2
from psycopg2 import sql
from psycopg2.extras import RealDictCursor, execute_batch

EXCLUDED_TABLES = {"flyway_schema_history", "knowledge_chunks"}
# 应用启动后会自己写行的表：迁移数据必须全部存在，但允许目标库多出应用新写的行
APP_WRITES_TABLES = {"audit_logs", "ai_providers"}


def parse_args():
    parser = argparse.ArgumentParser(description="EchoLint MySQL -> PostgreSQL 数据迁移")
    parser.add_argument("--apply", action="store_true",
                        help="执行迁移（默认 dry-run，只打印映射与行数）")
    parser.add_argument("--env-file", default=".env",
                        help="从该 .env 读取连接变量（默认 ./.env；不存在则忽略）")
    parser.add_argument("--mysql-host", default=os.environ.get("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.environ.get("MYSQL_PORT", "3306")))
    parser.add_argument("--mysql-user", default=os.environ.get("MYSQL_USER", "echolint"))
    parser.add_argument("--mysql-db", default=os.environ.get("MYSQL_DB", "echolint"))
    parser.add_argument("--mysql-password", default=os.environ.get("MYSQL_PASSWORD"))
    parser.add_argument("--pg-host", default=os.environ.get("PG_HOST", "127.0.0.1"))
    parser.add_argument("--pg-port", type=int, default=int(os.environ.get("PG_PORT", "5433")))
    parser.add_argument("--pg-user", default=os.environ.get("PG_USER", "audit"))
    parser.add_argument("--pg-db", default=os.environ.get("PG_DB", "echolint"))
    parser.add_argument("--pg-password", default=os.environ.get("PG_PASSWORD"))
    return parser.parse_args()


def load_env_file(path):
    env = {}
    if not path or not os.path.exists(path):
        return env
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def resolve(args, env_file):
    def pick(cli_value, env_key, fallback=None):
        if cli_value is not None:
            return cli_value
        if env_key in env_file:
            return env_file[env_key]
        return fallback

    mysql_password = pick(args.mysql_password, "ECHOLINT_DB_PASSWORD") or env_file.get("MYSQL_PASSWORD")
    pg_password = pick(args.pg_password, "POSTGRES_PASSWORD") or env_file.get("PG_PASSWORD")
    if not mysql_password:
        sys.exit("[FAIL] 缺少 MySQL 密码：--mysql-password / MYSQL_PASSWORD / ECHOLINT_DB_PASSWORD")
    if not pg_password:
        sys.exit("[FAIL] 缺少 PostgreSQL 密码：--pg-password / PG_PASSWORD / POSTGRES_PASSWORD")
    return mysql_password, pg_password


def mysql_conn(args, password):
    return pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=password,
        database=args.mysql_db,
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def pg_conn(args, password):
    conn = psycopg2.connect(
        host=args.pg_host,
        port=args.pg_port,
        user=args.pg_user,
        password=password,
        dbname=args.pg_db,
        client_encoding="UTF8",
    )
    conn.autocommit = False
    return conn


def lower_keys(rows):
    return [{k.lower(): v for k, v in row.items()} for row in rows]


def source_tables(cur, database):
    cur.execute(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = %s AND table_type = 'BASE TABLE' ORDER BY table_name",
        (database,),
    )
    rows = lower_keys(cur.fetchall())
    return [row["table_name"] for row in rows if row["table_name"] not in EXCLUDED_TABLES]


def source_columns(cur, database, table):
    cur.execute(
        "SELECT column_name, data_type, column_type, is_nullable, extra "
        "FROM information_schema.columns WHERE table_schema = %s AND table_name = %s "
        "ORDER BY ordinal_position",
        (database, table),
    )
    return lower_keys(cur.fetchall())


def target_columns(cur, table):
    cur.execute(
        "SELECT column_name, data_type, is_nullable, column_default "
        "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = %s",
        (table,),
    )
    return {row["column_name"]: row for row in cur.fetchall()}


def convert(value, mysql_type, mysql_column_type, pg_type):
    if value is None:
        return None
    mysql_type = mysql_type.lower()
    if mysql_type == "bit":
        if isinstance(value, (bytes, bytearray)):
            return int.from_bytes(value, "big") != 0
        return bool(int(value))
    if mysql_type == "tinyint" and mysql_column_type.lower().startswith("tinyint(1)"):
        return bool(int(value))
    if pg_type == "boolean":
        if isinstance(value, bool):
            return value
        if isinstance(value, (bytes, bytearray)):
            return value not in (b"\x00", b"")
        return bool(int(value))
    if mysql_type in ("datetime", "timestamp"):
        if not isinstance(value, datetime.datetime) or value.year < 1900:
            return None
        return value
    if mysql_type == "date":
        if not isinstance(value, datetime.date) or value.year < 1900:
            return None
        return value
    if mysql_type == "decimal":
        return value if isinstance(value, decimal.Decimal) else decimal.Decimal(str(value))
    if mysql_type in ("blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary"):
        return psycopg2.Binary(bytes(value)) if isinstance(value, (bytes, bytearray)) else value
    return value


def main():
    args = parse_args()
    env_file = load_env_file(args.env_file)
    mysql_password, pg_password = resolve(args, env_file)
    my = mysql_conn(args, mysql_password)
    pg = pg_conn(args, pg_password)
    my_cur = my.cursor()
    pg_cur = pg.cursor(cursor_factory=RealDictCursor)

    plan = []
    errors = []
    for table in source_tables(my_cur, args.mysql_db):
        target = target_columns(pg_cur, table)
        if not target:
            errors.append(f"{table}: 目标库不存在该表")
            continue
        src_cols = source_columns(my_cur, args.mysql_db, table)
        common = [c for c in src_cols if c["column_name"] in target]
        missing_in_pg = [c["column_name"] for c in src_cols if c["column_name"] not in target]
        extra_notnull = [
            name
            for name, meta in target.items()
            if name not in {c["column_name"] for c in src_cols}
            and meta["is_nullable"] == "NO"
            and meta["column_default"] is None
        ]
        if missing_in_pg:
            errors.append(f"{table}: 源列在目标库不存在 {missing_in_pg}")
        if extra_notnull:
            errors.append(f"{table}: 目标 NOT NULL 列无源数据且无默认值 {extra_notnull}")
        my_cur.execute(f"SELECT COUNT(*) AS n FROM `{table}`")
        plan.append({
            "table": table,
            "columns": [c["column_name"] for c in common],
            "colmeta": common,
            "rows": my_cur.fetchone()["n"],
        })

    print(f"{'table':20s} {'src_rows':>9s} {'columns':>8s}")
    for item in plan:
        print(f"{item['table']:20s} {item['rows']:9d} {len(item['columns']):8d}")
    print(f"\n共 {len(plan)} 张表，源行数合计 {sum(i['rows'] for i in plan)}")

    if errors:
        print("\n[FAIL] 结构校验未通过：")
        for err in errors:
            print("  -", err)
        return 1

    if not args.apply:
        print("\n[DRY-RUN] 未写入任何数据；加 --apply 执行迁移。")
        return 0

    print("\n[1/4] TRUNCATE 目标业务表 ...")
    pg_cur.execute(
        sql.SQL("TRUNCATE TABLE {} RESTART IDENTITY CASCADE").format(
            sql.SQL(", ").join(sql.Identifier(i["table"]) for i in plan)
        )
    )

    print("[2/4] 逐表搬迁 ...")
    for item in plan:
        table, cols = item["table"], item["columns"]
        if not cols:
            continue
        if item["rows"] == 0:
            print(f"  {table:20s} 0 rows (skip)")
            continue
        my_cur.execute("SELECT {} FROM `{}`".format(
            ", ".join(f"`{c}`" for c in cols), table))
        rows = my_cur.fetchall()
        pg_meta_map = target_columns(pg_cur, table)
        data = []
        for row in rows:
            data.append(tuple(
                convert(row[col["column_name"]], col["data_type"], col["column_type"],
                        pg_meta_map[col["column_name"]]["data_type"])
                for col in item["colmeta"]
            ))
        insert_sql = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            sql.Identifier(table),
            sql.SQL(", ").join(sql.Identifier(c) for c in cols),
            sql.SQL(", ").join(sql.Placeholder() * len(cols)),
        )
        execute_batch(pg_cur, insert_sql, data, page_size=200)
        print(f"  {table:20s} {len(data)} rows inserted")

    print("[3/4] 重置 identity 序列 ...")
    pg_cur.execute(
        "SELECT table_name FROM information_schema.columns "
        "WHERE table_schema = 'public' AND column_name = 'id' ORDER BY table_name"
    )
    for row in pg_cur.fetchall():
        table = row["table_name"]
        if table == "flyway_schema_history":
            continue
        pg_cur.execute(
            sql.SQL(
                "SELECT setval(pg_get_serial_sequence(%s, 'id'), "
                "GREATEST(COALESCE(MAX(id), 1), 1), COALESCE(MAX(id), 0) > 0) FROM {}"
            ).format(sql.Identifier(table)),
            (table,),
        )
    print("  done")

    print("[4/4] 行数校验 ...")
    failures = 0
    for item in plan:
        table = item["table"]
        pg_cur.execute(sql.SQL("SELECT COUNT(*) AS n FROM {}").format(sql.Identifier(table)))
        got = pg_cur.fetchone()["n"]
        ok = got == item["rows"]
        failures += 0 if ok else 1
        print(f"  {table:20s} mysql={item['rows']:6d} pg={got:6d} {'OK' if ok else 'MISMATCH'}")

    if failures:
        pg.rollback()
        print(f"\n[FAIL] {failures} 张表行数不一致，已回滚。")
        return 1
    pg.commit()
    print("\n[OK] 迁移完成并已提交。")
    print("下一步：重启后端；按 docs/postgres-migration.md §4 做聚合/时间/向量校验。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
