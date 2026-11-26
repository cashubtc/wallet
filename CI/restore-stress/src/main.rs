//! Populate a fakewallet mint with a wallet that has many derived secrets.
//!
//! usage: restore-stress <mint-url> "<mnemonic>" [rounds=20] [sats-per-round=500] [spend-every=3]
//!
//! Each round mints `sats-per-round` sats split into 1-sat proofs, so every
//! round consumes `sats-per-round` derivation indices. Every `spend-every`th
//! round also sends and receives back a token, which leaves spent proofs on the
//! mint and advances the counter further. A restore from the same mnemonic must
//! scan all of it.

use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use bip39::Mnemonic;
use cdk::amount::SplitTarget;
use cdk::nuts::{CurrencyUnit, PaymentMethod};
use cdk::wallet::{ReceiveOptions, SendOptions, Wallet};
use cdk::Amount;
use cdk_sqlite::wallet::memory;

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let mint_url = args.next().context("mint url required")?;
    let mnemonic = Mnemonic::from_str(&args.next().context("mnemonic required")?)?;
    let rounds: u64 = args.next().map(|v| v.parse()).transpose()?.unwrap_or(20);
    let per_round: u64 = args.next().map(|v| v.parse()).transpose()?.unwrap_or(500);
    let spend_every: u64 = args.next().map(|v| v.parse()).transpose()?.unwrap_or(3);

    let seed = mnemonic.to_seed("");
    let store = Arc::new(memory::empty().await?);
    let wallet = Wallet::new(&mint_url, CurrencyUnit::Sat, store, seed, None)?;

    let mut minted: u64 = 0;
    for round in 1..=rounds {
        let quote = wallet
            .mint_quote(PaymentMethod::BOLT11, Some(Amount::from(per_round)), None, None)
            .await?;
        let proofs = wallet
            .wait_and_mint_quote(quote, SplitTarget::Value(Amount::from(1)), None, Duration::from_secs(120))
            .await?;
        minted += per_round;
        println!("round {round}/{rounds}: minted {per_round} sat as {} proofs", proofs.len());

        if spend_every > 0 && round % spend_every == 0 {
            // Send part of the balance and take it straight back: the original
            // proofs become SPENT at the mint and fresh secrets are derived.
            let send_amount = Amount::from(per_round / 2);
            let prepared = wallet.prepare_send(send_amount, SendOptions::default()).await?;
            let token = prepared.confirm(None).await?;
            wallet.receive(&token.to_string(), ReceiveOptions::default()).await?;
            println!("round {round}: sent and re-received {send_amount} sat");
        }
    }

    let balance = wallet.total_balance().await?;
    println!("DONE minted_total={minted} balance={balance}");
    Ok(())
}
