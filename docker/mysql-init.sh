set -eu
mysql --protocol=socket -uroot --password="${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < /schema/im-platform.sql
if [ -f /schema/sensitive-words.sql ]; then
  mysql --protocol=socket -uroot --password="${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < /schema/sensitive-words.sql
fi
find /schema/updates -name '*.sql' | sort | while read -r file; do
  mysql --protocol=socket -uroot --password="${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < "$file"
done
