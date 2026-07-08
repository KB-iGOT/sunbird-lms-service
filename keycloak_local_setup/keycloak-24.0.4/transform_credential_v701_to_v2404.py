import csv
import json
import hashlib
import time
from datetime import datetime

# Max number of INSERT statements per output .sql file. With ~30 lac (3,000,000)
# rows and this set to 1,000,000, each transform produces 3 files.
MAX_ROWS_PER_FILE = 1_000_000


class ChunkedSqlWriter:
    """Writes SQL statements across multiple files, rotating after max_rows.

    Chunks are named base_name_part1.sql, base_name_part2.sql, etc.
    """

    def __init__(self, base_name, max_rows=MAX_ROWS_PER_FILE):
        self.base_name = base_name
        self.max_rows = max_rows
        self.chunk_index = 1
        self.row_count = 0
        self.out = open(self._filename(), 'w')

    def _filename(self):
        return f"{self.base_name}_part{self.chunk_index}.sql"

    def write(self, sql):
        if self.row_count >= self.max_rows:
            self.out.close()
            self.chunk_index += 1
            self.row_count = 0
            self.out = open(self._filename(), 'w')
        self.out.write(sql + '\n')
        self.row_count += 1

    def close(self):
        self.out.close()


def transform_fed_credentials():
    with open('fed_user_credentials.csv') as f:
        reader = csv.DictReader(f)
        writer = ChunkedSqlWriter('insert_fed_creds_24')
        for row in reader:
            # Handle null values
            algorithm = row.get('algorithm') or 'pbkdf2-sha1'
            hash_iterations = int(row.get('hash_iterations') or 20000)

            # Correct JSON structure for 24.0.4
            secret_data = {
                "value": row['value'],
                "salt": row['salt_b64'] if row['salt_b64'] else "",
                "additionalParameters": {}
            }

            credential_data = {
                "algorithm": algorithm,
                "hashIterations": hash_iterations,
                "additionalParameters": {}
            }

            # Escape quotes for SQL
            secret_json = json.dumps(secret_data).replace("'", "''")
            cred_json = json.dumps(credential_data).replace("'", "''")

            updated_user_id = row['user_id'].replace('f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:', 'f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:')

            sql = f"""INSERT INTO fed_user_credential (id, user_id, realm_id, storage_provider_id, type, secret_data, credential_data, priority, created_date) VALUES ('{row['id']}', '{updated_user_id}', '{row['realm_id']}', '91ec95d2-a3d5-413e-b4e4-53b0dcc96485', '{row['type']}', '{secret_json}', '{cred_json}', 10, {row['created_date'] or 'NOW()'});"""
            writer.write(sql)
        writer.close()


def transform_fed_users():
    with open('federated_users.csv') as f:
        reader = csv.DictReader(f)
        writer = ChunkedSqlWriter('insert_fed_users_24')
        for row in reader:
            update_id = row['id'].replace('f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:', 'f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:')
            sql = f"""INSERT INTO federated_user (id, storage_provider_id, realm_id) VALUES ('{update_id}', '91ec95d2-a3d5-413e-b4e4-53b0dcc96485', '{row['realm_id']}');"""
            writer.write(sql)
        writer.close()


def transform_fed_attributes():
    with open('fed_user_attributes.csv') as f:
        reader = csv.DictReader(f)
        writer = ChunkedSqlWriter('insert_fed_attrs_24')
        for row in reader:
            # Handle long values for 24.0.4 format
            value = row['value'] or ''
            long_value = None
            long_value_hash = None

            if len(value) > 255:
                long_value = value
                value = None
                # Generate hash for long values (simplified)
                long_value_hash = hashlib.sha256(long_value.encode()).digest().hex()

            updated_user_id = row['user_id'].replace('f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:', 'f:91ec95d2-a3d5-413e-b4e4-53b0dcc96485:')

            if long_value:
                sql = f"""INSERT INTO fed_user_attribute (id, name, user_id, realm_id, storage_provider_id, value, long_value, long_value_hash) VALUES ('{row['id']}', '{row['name']}', '{updated_user_id}', '{row['realm_id']}', '91ec95d2-a3d5-413e-b4e4-53b0dcc96485', NULL, '{long_value}', decode('{long_value_hash}', 'hex'));"""
            else:
                sql = f"""INSERT INTO fed_user_attribute (id, name, user_id, realm_id, storage_provider_id, value) VALUES ('{row['id']}', '{row['name']}', '{updated_user_id}', '{row['realm_id']}', '91ec95d2-a3d5-413e-b4e4-53b0dcc96485', '{value}');"""
            writer.write(sql)
        writer.close()


# Run transformations
start_time = datetime.now()
start_perf = time.perf_counter()
print(f"Start time: {start_time}")

transform_fed_users()
transform_fed_credentials()
transform_fed_attributes()

end_time = datetime.now()
elapsed_seconds = time.perf_counter() - start_perf
print(f"End time: {end_time}")
print(f"Time taken: {elapsed_seconds:.2f} seconds")
