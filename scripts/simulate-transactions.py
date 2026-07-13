#!/usr/bin/env python3
"""
Transaction simulation script for the Real-Time Transaction Risk Engine.
Generates synthetic transactions with occasional velocity spikes to demonstrate
the risk engine's capabilities.

Usage:
    python simulate-transactions.py [--base-url URL] [--spike-probability P]
"""

import argparse
import json
import random
import string
import time
import uuid
from datetime import datetime

import requests


def random_id(prefix="user"):
    """Generate a random ID with an optional prefix."""
    return f"{prefix}-{''.join(random.choices(string.ascii_lowercase + string.digits, k=8))}"


def generate_transaction(users, merchants, devices, spike_mode=False):
    """Generate a synthetic transaction."""
    txn_id = str(uuid.uuid4())

    # In spike mode, use the same user repeatedly to trigger velocity checks
    if spike_mode and random.random() < 0.7:
        user_id = random.choice(users[:3])  # Concentrate on first 3 users
    else:
        user_id = random.choice(users)

    merchant_id = random.choice(merchants)
    device_id = random.choice(devices)

    # Amount distribution: mostly small, occasionally large
    if random.random() < 0.05:
        amount = round(random.uniform(5000, 50000), 2)  # High amount
    elif random.random() < 0.15:
        amount = round(random.uniform(1000, 5000), 2)  # Medium amount
    else:
        amount = round(random.uniform(1, 500), 2)  # Typical amount

    timestamp = datetime.utcnow().isoformat()

    return {
        "transactionId": txn_id,
        "userId": user_id,
        "merchantId": merchant_id,
        "deviceId": device_id,
        "amount": amount,
        "timestamp": timestamp
    }


def send_transaction(base_url, transaction):
    """Send a transaction to the risk engine API."""
    url = f"{base_url}/api/transactions"
    headers = {"Content-Type": "application/json"}

    try:
        response = requests.post(url, json=transaction, headers=headers, timeout=5)
        if response.status_code == 202:
            result = response.json()
            print(f"✓ Accepted: {transaction['transactionId'][:8]}... "
                  f"| User: {transaction['userId'][:12]:12s} "
                  f"| Amount: ${transaction['amount']:>8.2f} "
                  f"| Status: {result['status']}")
            return True
        else:
            print(f"✗ Failed: {response.status_code} - {response.text[:100]}")
            return False
    except requests.exceptions.RequestException as e:
        print(f"✗ Error: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Simulate transactions for the Risk Engine"
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Base URL of the risk engine API (default: http://localhost:8080)"
    )
    parser.add_argument(
        "--total-transactions",
        type=int,
        default=100,
        help="Total number of transactions to send (default: 100)"
    )
    parser.add_argument(
        "--spike-probability",
        type=float,
        default=0.15,
        help="Probability of entering velocity spike mode (default: 0.15)"
    )
    parser.add_argument(
        "--delay-min",
        type=float,
        default=0.1,
        help="Minimum delay between transactions in seconds (default: 0.1)"
    )
    parser.add_argument(
        "--delay-max",
        type=float,
        default=0.5,
        help="Maximum delay between transactions in seconds (default: 0.5)"
    )

    args = parser.parse_args()

    # Generate synthetic entities
    users = [random_id("user") for _ in range(20)]
    merchants = [random_id("merchant") for _ in range(10)]
    devices = [random_id("device") for _ in range(15)]

    print(f"╔══════════════════════════════════════════════════════════╗")
    print(f"║   Transaction Risk Engine - Simulation                  ║")
    print(f"╠══════════════════════════════════════════════════════════╣")
    print(f"║  Users: {len(users):<3d} | Merchants: {len(merchants):<3d} | Devices: {len(devices):<3d}      ║")
    print(f"║  Total TXNs: {args.total_transactions:<4d} | Spike Prob: {args.spike_probability:.0%}          ║")
    print(f"╚══════════════════════════════════════════════════════════╝")
    print()

    # Check health first
    try:
        health_resp = requests.get(f"{args.base_url}/api/health", timeout=5)
        if health_resp.status_code == 200:
            print(f"✓ Health check OK: {health_resp.json()}")
        else:
            print(f"⚠ Health check returned: {health_resp.status_code}")
    except requests.exceptions.RequestException as e:
        print(f"✗ Cannot reach {args.base_url}/api/health - {e}")
        print(f"  Make sure the risk engine is running before simulating.")
        return

    print(f"\n{'─' * 50}")
    print(f"Starting simulation with {args.total_transactions} transactions...")
    print(f"{'─' * 50}\n")

    sent = 0
    spike_mode = False

    for i in range(args.total_transactions):
        # Periodically switch to spike mode to trigger velocity checks
        if random.random() < args.spike_probability:
            spike_mode = True
            spike_duration = random.randint(5, 15)
            print(f"\n⚠ SPIKE MODE ACTIVATED — next {spike_duration} transactions on same users!\n")
        elif spike_mode:
            spike_duration -= 1
            if spike_duration <= 0:
                spike_mode = False
                print(f"\n✓ Spike mode ended. Returning to normal traffic.\n")

        transaction = generate_transaction(users, merchants, devices, spike_mode)
        if send_transaction(args.base_url, transaction):
            sent += 1

        # Delay between transactions
        delay = random.uniform(args.delay_min, args.delay_max)
        time.sleep(delay)

    print(f"\n{'─' * 50}")
    print(f"Simulation complete!")
    print(f"  Total sent: {sent}/{args.total_transactions}")
    print(f"{'─' * 50}")


if __name__ == "__main__":
    main()