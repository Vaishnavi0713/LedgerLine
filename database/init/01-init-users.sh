#!/bin/bash
set -euo pipefail

# ==============================================================================
# LedgerLine — MySQL User Provisioning & Privilege Hardening Script
# ==============================================================================
# Executed by the official MySQL 8.0 Docker entrypoint on container initialization.
# Provisions separate users:
# 1. Migration user ('ledgerline_migrator') - DDL and Flyway schema management
# 2. Routine Definer user ('ledgerline_definer') - Narrow DML scope for stored procedures
# 3. Application runtime user ('ledgerline_app') - Least privilege operational user
# ==============================================================================

# 1. Validation Allowlist Patterns
IDENTIFIER_REGEX='^[a-zA-Z0-9_]{1,64}$'
PASSWORD_REGEX='^[a-zA-Z0-9_.~-]{16,128}$'

# 2. Check for required environment variables
for required_var in MYSQL_ROOT_PASSWORD DB_MIGRATOR_PASSWORD DB_APP_PASSWORD; do
    if [ -z "${!required_var:-}" ]; then
        echo "ERROR: Required environment variable '${required_var}' is missing or empty." >&2
        exit 1
    fi
done

# 3. Apply default values for database identifiers and definer password
DB_NAME="${DB_NAME:-ledgerline_db}"
DB_MIGRATOR_USER="${DB_MIGRATOR_USER:-ledgerline_migrator}"
DB_APP_USER="${DB_APP_USER:-ledgerline_app}"
DB_DEFINER_USER="${DB_DEFINER_USER:-ledgerline_definer}"

# If definer password not supplied, generate a cryptographically strong URL-safe password
if [ -z "${DB_DEFINER_PASSWORD:-}" ]; then
    DB_DEFINER_PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9_.~-' | head -c 32)"
fi

# 4. Validate identifier formats against safe allowlist
for id_var in DB_NAME DB_MIGRATOR_USER DB_APP_USER DB_DEFINER_USER; do
    val="${!id_var}"
    if [[ ! "$val" =~ $IDENTIFIER_REGEX ]]; then
        echo "ERROR: '${id_var}' value '${val}' is invalid. Allowed: alphanumeric and underscore (1-64 characters)." >&2
        exit 1
    fi
done

# 5. Validate password complexity and character allowlist without printing secret values
for pass_var in MYSQL_ROOT_PASSWORD DB_MIGRATOR_PASSWORD DB_APP_PASSWORD DB_DEFINER_PASSWORD; do
    val="${!pass_var}"
    if [[ ! "$val" =~ $PASSWORD_REGEX ]]; then
        echo "ERROR: '${pass_var}' does not comply with the password policy." >&2
        echo "Policy: 16 to 128 characters using URL-safe characters [a-zA-Z0-9_.~-]." >&2
        exit 1
    fi
done

echo "Environment validation succeeded for database '${DB_NAME}', migrator '${DB_MIGRATOR_USER}', definer '${DB_DEFINER_USER}', and app '${DB_APP_USER}'."

# 6. Secure Option File Setup (Avoids exposing root password on CLI process table)
CNF_FILE="$(mktemp /tmp/mysql-init-XXXXXX.cnf)"
chmod 600 "$CNF_FILE"

cleanup() {
    if [[ -n "${CNF_FILE:-}" && -f "$CNF_FILE" ]]; then
        dd if=/dev/zero of="$CNF_FILE" bs=1024 count=1 2>/dev/null || true
        rm -f "$CNF_FILE"
    fi
}
trap cleanup EXIT HUP INT QUIT TERM

cat <<EOF > "$CNF_FILE"
[client]
user=root
password="${MYSQL_ROOT_PASSWORD}"
protocol=socket
EOF

echo "Configuring database schema and user privileges..."

# 7. Execute DDL & Grants via socket using the protected option file
mysql --defaults-extra-file="$CNF_FILE" <<EOSQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. Migration User (Flyway DDL user: schema, trigger, procedure creation and migration capabilities)
CREATE USER IF NOT EXISTS '${DB_MIGRATOR_USER}'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_MIGRATOR_USER}'@'%' WITH GRANT OPTION;
GRANT SET_USER_ID ON *.* TO '${DB_MIGRATOR_USER}'@'%';

-- 2. Dedicated Routine Definer (Narrow DML scope for stored procedures: no DDL, no GRANT, no DELETE)
CREATE USER IF NOT EXISTS '${DB_DEFINER_USER}'@'%' IDENTIFIED BY '${DB_DEFINER_PASSWORD}';
GRANT SELECT, UPDATE ON \`${DB_NAME}\`.accounts TO '${DB_DEFINER_USER}'@'%';
GRANT SELECT, UPDATE ON \`${DB_NAME}\`.transactions TO '${DB_DEFINER_USER}'@'%';
GRANT SELECT, INSERT ON \`${DB_NAME}\`.ledger_entries TO '${DB_DEFINER_USER}'@'%';
GRANT SELECT, INSERT ON \`${DB_NAME}\`.statement_events TO '${DB_DEFINER_USER}'@'%';

-- 3. Application Runtime User (Least Privilege: Read-only at database level)
-- Table-specific INSERT and UPDATE on operational tables are granted by Flyway migrations.
-- Direct INSERT, UPDATE, and DELETE are strictly forbidden on ledger_entries and statement_events.
CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
GRANT SELECT ON \`${DB_NAME}\`.* TO '${DB_APP_USER}'@'%';

FLUSH PRIVILEGES;
EOSQL

echo "Database initialization completed successfully."
