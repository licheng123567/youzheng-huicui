# 从备份恢复

> **没有演练过的备份 = 没有备份。**
> 这套备份是加密的，解开它需要**离线保管的私钥**。如果你从没试过恢复，
> 那么你既不知道私钥还在不在，也不知道流程走不走得通 —— 而你会在最糟的那天才发现。
> **上线前务必按第 3 节演练一次。**

## 0. 你需要什么

| 东西 | 在哪 |
|---|---|
| `huicui-YYYYmmddTHHMMSSZ-<tag>.sql.gz.enc` | 备份文件（服务器 `deploy/backup/`，或异地对象存储） |
| `huicui-...key.enc` | 同名的**密钥信封**（跟备份文件成对，缺一不可） |
| `huicui-backup-private.pem` | **离线私钥**。不在服务器上——在你的密码管理器/保险柜里 |

> 私钥丢了 = 备份永久打不开。**没有后门，也不该有后门。**
> 它是唯一能把业主 PII 从密文里解出来的东西，请当作最高级别的凭据保管。

## 1. 解密

在一台**可信的机器**上做（不是被入侵的那台服务器）：

```bash
# 1) 用私钥拆开密钥信封，取出这份备份的一次性 AES 密钥
DEK="$(openssl pkeyutl -decrypt -inkey huicui-backup-private.pem \
        -pkeyopt rsa_padding_mode:oaep -in huicui-XXXX.key.enc)"

# 2) 用它解开备份本体
export DEK
openssl enc -d -aes-256-cbc -pbkdf2 -pass env:DEK \
  -in huicui-XXXX.sql.gz.enc -out huicui-XXXX.sql.gz
unset DEK

# 3) 校验它是完整的（截断的 dump 比没有备份更危险）
zcat huicui-XXXX.sql.gz | tail -5 | grep "PostgreSQL database dump complete"
```

## 2. 恢复到生产（真出事时）

```bash
# 停应用，只留数据库（避免恢复期间有人写入）
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env stop backend web

# 建一个空库并灌入（**不要直接往有数据的库里灌**，先确认这是你要的那份）
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T db \
  psql -U "$POSTGRES_USER" -c "DROP DATABASE IF EXISTS huicui_restored; CREATE DATABASE huicui_restored;"

zcat huicui-XXXX.sql.gz | docker compose -f deploy/docker-compose.prod.yml \
  --env-file deploy/.env exec -T db psql -U "$POSTGRES_USER" -d huicui_restored

# 抽查：账号数/案件数/回款总额对不对得上你记忆中的量级？
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T db \
  psql -U "$POSTGRES_USER" -d huicui_restored -c \
  "SELECT (SELECT count(*) FROM account) AS 账号, (SELECT count(*) FROM \"case\") AS 案件,
          (SELECT coalesce(sum(amount_cents),0)/100.0 FROM repay_line WHERE NOT reversed) AS 回款元;"

# 确认无误后再切换（改 POSTGRES_DB 或 rename），然后起应用
```

## 3. 恢复演练（上线前必做一次）

**在非生产库上做**，走一遍第 1、2 节，确认三件事：

1. **私钥还在，且能拆开密钥信封** —— 这是最容易在"以为有"的地方翻车的一步
2. dump 完整（有 `dump complete` 标记）
3. 灌进空库后，账号/案件/回款的数量级对得上

演练完把 `huicui_restored` 删掉。

## 4. 常见问题

| 现象 | 原因 | 处置 |
|---|---|---|
| `pkeyutl: Error decrypting` | 私钥和这份备份的公钥不是一对 | 换对应的私钥；公钥轮换过就得留好每一代私钥 |
| `bad decrypt` | 密钥信封与备份文件没配对（张冠李戴） | 两个文件名前缀必须完全一致 |
| 备份脚本报"找不到备份公钥" | 服务器上没放公钥 | 见 `deploy/backup.sh` 顶部注释生成并 scp 公钥 |
| 只有 `.sql.gz`（无 `.enc`） | 是用 `BACKUP_ALLOW_PLAINTEXT=true` 产出的明文备份 | 直接 `zcat` 即可；但**明文备份不该出现在生产** |
