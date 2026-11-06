#!/bin/bash
set -euo pipefail

# ==============================================================================
# LedgerLine — MySQL User Provisioning & Privilege Hardening Script
# ==============================================================================
# Executed by the official MySQL 8.0 Docker entrypoint on container initialization.
# Provisions separate migration ('ledgerline_migrator') and runtime ('ledgerline_app') users.
# ==============================================================================

# 1. Validation Allowlist Patterns
# Identifiers: Alphanumeric and underscores, 1 to 64 characters (standard MySQL unquoted identifier)
IDENTIFIER_REGEX='^[a-zA-Z0-9_]{1,64}$'

# Passwords: URL-safe unreserved characters (RFC 3986): alphanumeric, hyphen, underscore, period, tilde.
# Prevents SQL injection, quote escaping, and command interpolation vulnerabilities.
# Minimum 16 characters for entropy; maximum 128 characters.
PASSWORD_REGEX='^[a-zA-Z0-9_.~-]{16,128}$'

# 2. Check for required environment variables
for required_var in MYSQL_ROOT_PASSWORD DB_MIGRATOR_PASSWORD DB_APP_PASSWORD; do
    if [ -z "${!required_var:-}" ]; then
        echo "ERROR: Required environment variable '${required_var}' is missing or empty." >&2
        exit 1
    fi
done

# 3. Apply default values for database identifiers if not explicitly provided
DB_NAME="${DB_NAME:-ledgerline_db}"
DB_MIGRATOR_USER="${DB_MIGRATOR_USER:-ledgerline_migrator}"
DB_APP_USER="${DB_APP_USER:-ledgerline_app}"

# 4. Validate identifier formats against safe allowlist
for id_var in DB_NAME DB_MIGRATOR_USER DB_APP_USER; do
    val="${!id_var}"
    if [[ ! "$val" =~ $IDENTIFIER_REGEX ]]; then
        echo "ERROR: '${id_var}' value '${val}' is invalid. Allowed: alphanumeric and underscore (1-64 characters)." >&2
        exit 1
    fi
done

# 5. Validate password complexity and character allowlist without printing secret values
for pass_var in MYSQL_ROOT_PASSWORD DB_MIGRATOR_PASSWORD DB_APP_PASSWORD; do
    val="${!pass_var}"
    if [[ ! "$val" =~ $PASSWORD_REGEX ]]; then
        echo "ERROR: '${pass_var}' does not comply with the password policy." >&2
        echo "Policy: 16 to 128 characters using URL-safe characters [a-zA-Z0-9_.~-]." >&2
        exit 1
    fi
done

echo "Environment validation succeeded for database '${DB_NAME}', migrator '${DB_MIGRATOR_USER}', and app '${DB_APP_USER}'."

# 6. Secure Option File Setup (Avoids exposing root password on CLI process table)
CNF_FILE="$(mktemp /tmp/mysql-init-XXXXXX.cnf)"
chmod 600 "$CNF_FILE"

cleanup() {
    if [[ -n "${CNF_FILE:-}" && -f "$CNF_FILE" ]]; then
        # Overwrite with zeros before removing
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

-- 1. Migration User (Flyway DDL user: full schema and migration capabilities)
CREATE USER IF NOT EXISTS '${DB_MIGRATOR_USER}'@'%' IDENTIFIED BY '${DB_MIGRATOR_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES, TRIGGER 
    ON \`${DB_NAME}\`.* TO '${DB_MIGRATOR_USER}'@'%';

-- 2. Application Runtime User (Least Privilege: DML only; no DDL, no GRANT)
CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${DB_NAME}\`.* TO '${DB_APP_USER}'@'%';

FLUSH PRIVILEGES;
EOSQL

echo "Database initialization completed successfully."
