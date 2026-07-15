#!/bin/sh

# A production build must have a support recipient. The app performs the full
# Bech32/TLV validation (including secure relay hints) before enabling Send.

set -eu

case "${CONFIGURATION:-}" in
    Release*) ;;
    *) exit 0 ;;
esac

VALUE="${NOSTR_ERROR_REPORT_NPROFILE:-}"
case "${VALUE}" in
    nprofile1??????????????*) ;;
    *)
        echo "error: Release builds require NOSTR_ERROR_REPORT_NPROFILE with an nprofile recipient and relay hints."
        exit 1
        ;;
esac

