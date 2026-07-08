#!/usr/bin/env python3
"""Generate a long-lived BOLT11 invoice for local FakeWallet melt tests."""

import argparse
import hashlib
import time

from bolt11 import Bolt11, Feature, Features, FeatureState, MilliSatoshi, Tag, TagChar, Tags, encode


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--amount-sats", type=int, default=3)
    parser.add_argument("--description", default="Android native matrix melt")
    parser.add_argument("--expiry-seconds", type=int, default=315_360_000)
    args = parser.parse_args()

    entropy = f"{time.time_ns()}:{args.amount_sats}:{args.description}".encode()
    payment_hash = hashlib.sha256(entropy).hexdigest()
    payment_secret = hashlib.sha256(entropy + b":secret").hexdigest()
    features = Features.from_feature_list(
        {
            Feature.payment_secret: FeatureState.supported,
            Feature.var_onion_optin: FeatureState.supported,
        }
    )
    tags = Tags(
        [
            Tag(TagChar.payment_hash, payment_hash),
            Tag(TagChar.description, args.description),
            Tag(TagChar.payment_secret, payment_secret),
            Tag(TagChar.features, features),
            Tag(TagChar.expire_time, args.expiry_seconds),
        ]
    )
    invoice = Bolt11(
        currency="bc",
        date=int(time.time()),
        tags=tags,
        amount_msat=MilliSatoshi(args.amount_sats * 1_000),
    )
    # Local integration-only signing key; not a wallet or mint secret.
    print(encode(invoice, private_key="0000000000000000000000000000000000000000000000000000000000000001"))


if __name__ == "__main__":
    main()
