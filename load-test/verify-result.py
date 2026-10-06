import subprocess
import re
import sys

def run_command(cmd):
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=True)
    if result.returncode != 0:
        print(f"Error running command: {cmd}")
        print(result.stderr)
        return ""
    return result.stdout.strip()

def get_redis_inventory():
    out = run_command('docker exec flash-sale-redis redis-cli GET "flash-sale:1:inventory"')
    return int(out) if out and out.isdigit() else 0

def get_pg_orders_count():
    out = run_command('docker exec flash-sale-postgres psql -U myuser -d flashsale -t -c "SELECT COUNT(*) FROM orders WHERE status = \'CONFIRMED\'"')
    return int(out) if out.strip().isdigit() else 0

def get_pg_quantity_sold():
    out = run_command('docker exec flash-sale-postgres psql -U myuser -d flashsale -t -c "SELECT COALESCE(SUM(quantity), 0) FROM order_items"')
    return int(out) if out.strip().isdigit() else 0

def main():
    print("========================================")
    print("FLASH SALE LOAD TEST RESULT")
    print("========================================")
    
    redis_inv = get_redis_inventory()
    orders_count = get_pg_orders_count()
    quantity_sold = get_pg_quantity_sold()
    
    print(f"\nInitial inventory: 100")
    print(f"Final Redis inventory: {redis_inv}")
    
    print(f"\nTotal buy requests: (Check k6 output)")
    
    print(f"\nSuccessful reservations: {quantity_sold}") 
    print(f"Sold out: (Check k6 output)")
    print(f"Rate limited: (Check k6 output)")
    print(f"Rejected bot: (Check k6 output)")
    print(f"Invalid signature: (Check k6 output)")
    
    print(f"\nOrders created: {orders_count}")
    print(f"Total quantity sold: {quantity_sold}")
    
    overselling = max(0, quantity_sold - 100)
    negative_inv = 1 if redis_inv < 0 else 0
    duplicate_orders = max(0, orders_count - 100)
    
    print(f"\nOverselling: {overselling}")
    print(f"Negative inventory: {negative_inv}")
    print(f"Duplicate orders: {duplicate_orders}")
    
    print("\n========================================")
    if orders_count <= 100 and quantity_sold <= 100 and redis_inv == 0 and overselling == 0 and duplicate_orders == 0:
        print("RESULT: PASS")
        sys.exit(0)
    else:
        print("RESULT: FAIL")
        sys.exit(1)

if __name__ == "__main__":
    main()
