# cloud-itonami-numbering

**AgentMail の電話番号版**として、Telnyx の番号 inventory を検索し、所有者の
Passkey 同意と named operator の承認を別々に通したうえで、電話番号を agent / bot
へ払い出す Cloud Itonami actor/service です。

```
Telnyx available numbers ──> quote ──> cloud-itonami-app Passkey consent
                                             │
                                             v
consent :1345  POST /commit ─────────────> pending
                                             │
operator :1346 POST /proposals/:ref/decide ─┤ named operator
                                             v
                                      Telnyx number order
                                             │
                                             v
                              owner subject ─ number ─ agent/bot route
```

番号の**所有主体**と着信の**配信先**は別です。所有主体は Passkey を持つ人・組織、
`assignee` は agent/bot、`route` は `did:` または `topic:` です。bot が電話を受けても、
そのことだけで番号を解放・移管する権限は得ません。

## 現在地

| 面 | 状態 |
|---|---|
| 番号検索 | Telnyx API v2 実装済み。mock で HTTP 実測済み |
| Passkey 同意 hand-off | `cloud-itonami-app :number` と配線済み |
| operator gate | 別 listener・別 token、実装/実測済み |
| Telnyx 発注・状態照合 | 実装済み。pending order の operator 再照合を含む。実アカウントでは未実行 |
| agent/bot への routing record | 実装/永続化/resolve API 実測済み |
| 解放 | Telnyx delete 実装。local record は即再利用せず quarantine |
| 実番号・本番 credential・課金 | **未実行** |

`committed` は provider が `success` を返し、regulatory requirements も満たした場合だけ。
operator が承認しても provider が無い、失敗した、pending の場合は
`approved-not-actuated` です。承認と番号取得を同じ成功に潰しません。

## API

### Consent surface — `127.0.0.1:1345`

| route | auth | purpose |
|---|---|---|
| `GET /healthz` | open | process/config state。購入成功の主張ではない |
| `GET /v1/available-numbers` | `X-NUMBER-CONSENT-TOKEN` | Telnyx inventory と価格の観測 |
| `POST /commit` | same | Passkey-approved proposal を actor governor へ渡す |
| `GET /proposals/:ref` | same | pending/committed/refused を読む |
| `GET /v1/numbers` | same | assigned/quarantined lines |
| `GET /v1/resolve?to=+…` | same | inbound number を agent/bot route へ解決 |

### Operator surface — `127.0.0.1:1346`

| route | auth | purpose |
|---|---|---|
| `GET /healthz` | open | listener health |
| `POST /proposals/:ref/decide` | `X-NUMBER-OPERATOR-TOKEN` | `approve` / `reject`。`by` 必須 |
| `POST /proposals/:ref/reconcile` | same | Telnyx pending order を order id で再照合。`by` 必須 |

Consent listener には decide route がありません。token を共有しても到達できないのではなく、
HTTP surface 自体が分離されています。

## Run

```bash
clojure -M:dev:test

NUMBER_CONSENT_TOKEN=... \
NUMBER_OPERATOR_TOKEN=... \
NUMBERING_ACTUATOR=mock \
clojure -M:dev:serve
```

mock はネットワークも課金も発生させません。実 Telnyx は明示 opt-in です:

```bash
NUMBERING_ACTUATOR=telnyx
TELNYX_API_KEY=...
TELNYX_REQUIREMENT_GROUP_ID=...   # または TELNYX_BUNDLE_ID。+81 発注は片方必須
TELNYX_CONNECTION_ID=...         # voice webhook / SIP connection
TELNYX_MESSAGING_PROFILE_ID=...  # SMS を使う場合
```

credential は環境変数から request 時だけ読み、state/ledger へ保存しません。
state は既定で `.numbering/state.edn` に atomic replace されます。保存先は
`NUMBERING_STATE_PATH` で変更できます。

## Price and regulatory boundary

検索時の `upfront` / `monthly` / `currency` / `observed-at` は Passkey digest に含まれます。
既定では5分を越えた quote、upfront または monthly が20を越える quote を拒否します
（`NUMBERING_MAX_*` で deployment が明示変更）。Telnyx の order API 自体には max-price
field がないため、検索と発注の間の価格不変性までは証明しません。

+81 の発注は `TELNYX_REQUIREMENT_GROUP_ID` または `TELNYX_BUNDLE_ID` が無ければ、
operator が承認しても provider を呼ばず `approved-not-actuated` になります。資格・住所・
本人確認書類が実際に受理されたことは、Telnyx order の `requirements_met` で再確認します。

Provider 契約の正本:

- [Telnyx official OpenAPI](https://github.com/team-telnyx/openapi/blob/master/openapi/spec3.json)
- [List available phone numbers](https://developers.telnyx.com/api-reference/phone-number-search/list-available-phone-numbers)
- [Create a number order](https://developers.telnyx.com/api-reference/number-orders/create-a-number-order)

## Crash and retry boundary

provider が pending を返した場合は order id を保存し、operator surface の `reconcile` が
`GET /number_orders/:id` だけを実行します。2つ目の order は作りません。provider call の
送信後、response を保存する前に process が落ちた場合も自動再発注はせず、Telnyx の
`customer_reference` から operator が既存 order を照合します。この interrupted-call の
自動発見と shared persistence は次の maturity slice です。

## Reuse

- 番号の E.164、allocation、lifecycle、quarantine は `kotoba-lang/phone` が正本。
- JSON encode/decode は `kotoba-lang/json`。host 固有 parser を再実装しません。
- Passkey consent と cross-domain posture は `cloud-itonami-app`。
- 音声 session は `denwaban` / `kotoba-lang/koe`。この repo は session dialog を持ちません。

## Verification

```bash
clojure -M:dev:test
clojure -M:lint
nbb --classpath ".:../../../../scripts:../../../../scripts/nbb_compat" \
  ../../../../scripts/jvm_new_surface_policy.cljs self-test
```

## License

GNU Affero General Public License v3.0 or later. See `LICENSE`.
