// Test-only addition to the CDK v0.18.0 mint saga test module.
// Run with CI/reproduce-mint-history.py; no CDK production changes.
    #[tokio::test]
    async fn wallet_report_repeated_bolt11_attempts_create_distinct_history_rows() {
        let db = create_test_db().await;
        let mock = Arc::new(MockMintConnector::new());
        mock.reset_default_mint_state();
        let wallet = create_test_wallet_with_mock(db.clone(), mock.clone()).await;
        let mut quote = test_mint_quote(test_mint_url());
        quote.state = MintQuoteState::Paid;
        quote.amount = Some(Amount::from(64));
        quote.amount_paid = Amount::from(64);
        let id = quote.id.clone();
        db.add_mint_quote(quote).await.unwrap();
        for _ in 0..5 {
            mock.push_post_mint_response(Err(Error::MintingDisabled));
            assert!(matches!(wallet.mint_unified(&id, SplitTarget::default(), None).await,
                Err(Error::MintingDisabled)));
        }
        let failed = wallet.list_transactions(None).await.unwrap();
        assert_eq!(failed.len(), 5);
        assert!(failed.iter().all(|tx| tx.quote_id.as_deref() == Some(id.as_str())
            && tx.status == TransactionStatus::Failed && tx.amount == Amount::from(64)));
        assert_eq!(wallet.total_balance().await.unwrap(), Amount::ZERO);
        mock.enable_mint_signing();
        let proofs = wallet.mint_unified(&id, SplitTarget::default(), None).await.unwrap();
        assert!(!proofs.is_empty());
        let rows = wallet.list_transactions(None).await.unwrap();
        assert_eq!(rows.len(), 6);
        assert_eq!(rows.iter().filter(|tx| tx.status == TransactionStatus::Completed).count(), 1);
        assert_eq!(rows.iter().map(|tx| tx.id().to_string()).collect::<std::collections::HashSet<_>>().len(), 6);
        assert!(rows.iter().all(|tx| tx.quote_id.as_deref() == Some(id.as_str())
            && tx.payment_request == rows[0].payment_request));
        assert_eq!(wallet.total_balance().await.unwrap(), Amount::from(64));
        println!("CONFIRMED: one BOLT11 quote, six distinct stored rows (five failed, one completed), balance 64");
    }
