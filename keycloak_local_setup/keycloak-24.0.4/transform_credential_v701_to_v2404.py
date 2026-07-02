import csv
import json

def transform_fed_credentials():
    with open('fed_user_credentials.csv') as f:
        reader = csv.DictReader(f)
        with open('insert_fed_creds_24.sql', 'w') as out:
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

                updated_user_id = row['user_id'].replace('f:baada8b1-e62e-44c4-a14d-6702ea90f496:', 'f:03a14f73-54bf-4749-be16-8e91f775bb35:')

                sql = f"""INSERT INTO fed_user_credential (id, user_id, realm_id, storage_provider_id, type, secret_data, credential_data, priority, created_date) VALUES ('{row['id']}', '{updated_user_id}', '{row['realm_id']}', '03a14f73-54bf-4749-be16-8e91f775bb35', '{row['type']}', '{secret_json}', '{cred_json}', 10, {row['created_date'] or 'NOW()'});"""
                out.write(sql + '\n')

def transform_fed_users():
    with open('federated_users.csv') as f:
        reader = csv.DictReader(f)
        with open('insert_fed_users_24.sql', 'w') as out:
            for row in reader:
                update_id = row['id'].replace('f:baada8b1-e62e-44c4-a14d-6702ea90f496:', 'f:03a14f73-54bf-4749-be16-8e91f775bb35:')
                sql = f"""INSERT INTO federated_user (id, storage_provider_id, realm_id) VALUES ('{update_id}', '03a14f73-54bf-4749-be16-8e91f775bb35', '{row['realm_id']}');"""
                out.write(sql + '\n')

def transform_fed_attributes():
    with open('fed_user_attributes.csv') as f:
        reader = csv.DictReader(f)
        with open('insert_fed_attrs_24.sql', 'w') as out:
            for row in reader:
                # Handle long values for 24.0.4 format
                value = row['value'] or ''
                long_value = None
                long_value_hash = None
                
                if len(value) > 255:
                    long_value = value
                    value = None
                    # Generate hash for long values (simplified)
                    import hashlib
                    long_value_hash = hashlib.sha256(long_value.encode()).digest().hex()

                updated_user_id = row['user_id'].replace('f:baada8b1-e62e-44c4-a14d-6702ea90f496:', 'f:03a14f73-54bf-4749-be16-8e91f775bb35:')

                if long_value:
                    sql = f"""INSERT INTO fed_user_attribute (id, name, user_id, realm_id, storage_provider_id, value, long_value, long_value_hash) VALUES ('{row['id']}', '{row['name']}', '{updated_user_id}', '{row['realm_id']}', '03a14f73-54bf-4749-be16-8e91f775bb35', NULL, '{long_value}', decode('{long_value_hash}', 'hex'));"""
                else:
                    sql = f"""INSERT INTO fed_user_attribute (id, name, user_id, realm_id, storage_provider_id, value) VALUES ('{row['id']}', '{row['name']}', '{updated_user_id}', '{row['realm_id']}', '03a14f73-54bf-4749-be16-8e91f775bb35', '{value}');"""
                out.write(sql + '\n')

# Run transformations
transform_fed_users()
transform_fed_credentials()
transform_fed_attributes()