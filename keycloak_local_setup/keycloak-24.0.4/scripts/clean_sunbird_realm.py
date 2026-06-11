#!/usr/bin/env python3
import json
import sys
import os

def clean_realm_for_keycloak24(input_file, output_file):
    print(f"Cleaning realm file: {input_file}")
    
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            realm_data = json.load(f)
    except Exception as e:
        print(f"Error reading JSON file: {e}")
        return False
    
    print(f"Original realm: {realm_data.get('realm', 'unknown')}")
    
    # Remove sections that cause script upload errors
    problematic_sections = []
    
    # Clean clients - remove authorization settings that cause issues
    if 'clients' in realm_data:
        for i, client in enumerate(realm_data['clients']):
            client_id = client.get('clientId', f'client-{i}')
            
            # Remove authorization settings completely for now
            if 'authorizationSettings' in client:
                print(f"Removing authorizationSettings from client: {client_id}")
                del client['authorizationSettings']
                problematic_sections.append(f"authorizationSettings from {client_id}")
            
            # Remove protocol mappers that might have scripts
            if 'protocolMappers' in client:
                safe_mappers = []
                for mapper in client['protocolMappers']:
                    mapper_type = mapper.get('protocolMapper', '')
                    # Remove script-based mappers
                    if 'script' not in mapper_type.lower():
                        safe_mappers.append(mapper)
                    else:
                        print(f"Removing script-based mapper: {mapper.get('name', 'unknown')}")
                        problematic_sections.append(f"script mapper from {client_id}")
                client['protocolMappers'] = safe_mappers
    
    # Remove authentication flows with scripts
    if 'authenticationFlows' in realm_data:
        safe_flows = []
        for flow in realm_data['authenticationFlows']:
            flow_alias = flow.get('alias', 'unknown')
            has_scripts = False
            
            # Check executions for scripts
            if 'authenticationExecutions' in flow:
                safe_executions = []
                for execution in flow['authenticationExecutions']:
                    authenticator = execution.get('authenticator', '')
                    if 'script' not in authenticator.lower():
                        safe_executions.append(execution)
                    else:
                        print(f"Removing script execution from flow: {flow_alias}")
                        has_scripts = True
                        problematic_sections.append(f"script execution from {flow_alias}")
                flow['authenticationExecutions'] = safe_executions
            
            safe_flows.append(flow)
        realm_data['authenticationFlows'] = safe_flows
    
    # Remove authenticator configs with scripts
    if 'authenticatorConfig' in realm_data:
        safe_configs = []
        for config in realm_data['authenticatorConfig']:
            config_alias = config.get('alias', 'unknown')
            config_data = config.get('config', {})
            
            # Check if config contains script references
            has_script = any('script' in str(v).lower() for v in config_data.values())
            if not has_script:
                safe_configs.append(config)
            else:
                print(f"Removing script-based authenticator config: {config_alias}")
                problematic_sections.append(f"script config {config_alias}")
        realm_data['authenticatorConfig'] = safe_configs
    
    # Clean required actions that might have scripts
    if 'requiredActions' in realm_data:
        safe_actions = []
        for action in realm_data['requiredActions']:
            provider_id = action.get('providerId', '')
            if 'script' not in provider_id.lower():
                safe_actions.append(action)
            else:
                print(f"Removing script-based required action: {provider_id}")
                problematic_sections.append(f"script action {provider_id}")
        realm_data['requiredActions'] = safe_actions
    
    # Set basic realm properties for compatibility
    realm_data['loginWithEmailAllowed'] = True
    realm_data['duplicateEmailsAllowed'] = False
    
    # Ensure we have basic timeouts
    if 'accessTokenLifespan' not in realm_data:
        realm_data['accessTokenLifespan'] = 300
    
    # Write cleaned realm
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(realm_data, f, indent=2, ensure_ascii=False)
        print(f"Cleaned realm saved to: {output_file}")
        print(f"Removed {len(problematic_sections)} problematic sections:")
        for section in problematic_sections:
            print(f"  - {section}")
        return True
    except Exception as e:
        print(f"Error writing cleaned file: {e}")
        return False

if __name__ == "__main__":
    input_file = "/home/jayaprakashnarayanaswamy/KB/keycloak_24/scripts/sunbird-realm.json"
    output_file = "/home/jayaprakashnarayanaswamy/KB/keycloak_24/scripts/sunbird-realm-clean.json"
    
    if len(sys.argv) > 1:
        input_file = sys.argv[1]
    if len(sys.argv) > 2:
        output_file = sys.argv[2]
    
    if not os.path.exists(input_file):
        print(f"Input file not found: {input_file}")
        print("Please download sunbird-realm.json first")
        sys.exit(1)
    
    success = clean_realm_for_keycloak24(input_file, output_file)
    sys.exit(0 if success else 1)
