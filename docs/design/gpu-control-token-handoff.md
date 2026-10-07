Cleanup
docs/product-and-development-report.md in the SaMD-App clone reverted with git checkout --. The edit had been 1 file, 2 insertions, 2 deletions (it downgraded the on-device SLM claim in §3.5 and the §6.4 checklist). git status --porcelain now returns zero lines. Nothing committed, nothing stashed, no other path touched.

One discrepancy in the brief: the clone here is at a219da9, not 8fb5257. It was still treated as non-authoritative and read-only throughout, and no line numbers from it are cited.

Step 1, serving repo
Check	Result
HEAD	be6cc049fdb6de9a87d5adc8f8e0287fca269061 = be6cc04. Unmoved.
git status --short	Three IDE-metadata entries only (.idea/compiler.xml, .idea/gradle.xml, .idea/vcs.xml), all present at session start.
models/gemma-4-E2B-pharma-merged/	Still absent. models/ holds only gemma-4-E2B-pharma-lora/, gemma-4-E2B-pharma-v2/, litert_bundle/.
Step 2, control-token truth (MEASURED)
Pin: revision 3e22461f65e89153144f8adb70e3b8c2cc9845a7, file /media/acps/twoTBDrive/huggingface_cache/hub/models--google--gemma-4-E2B-it/snapshots/3e22461f65e89153144f8adb70e3b8c2cc9845a7/tokenizer.json, SHA-256 cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f. The ~/.cache copy is byte-identical.

24 added special tokens; full id table is in the handoff. The load-bearing findings:

Reasoning channel is <|channel>thought\n … <channel|>, ids 100 and 101. The channel is named: thought is plain text, not a token. <|think|> (98) is a separate unpaired enable-flag, not a delimiter.
<unused*> exists: <unused0>..<unused6226>, 6,227 tokens, contiguous by name, in six disjoint id blocks (6-45, 53-97, 99, 102-104, 256001-258879, 258885-262143). None carry the special flag. Three ordinary prose tokens contain the substring unused and are not control tokens.
[multimodal] is id 5, present in vocab but not in added_tokens, so unflagged, and square-bracketed.
Turn grammar <|turn> 105 / <turn|> 106; media <|image|> 258880, <|audio|> 258881, <|video|> 258884, plus boi/eoi/boa/eoa.
Step 3, comparison set
The audit report on this machine does not contain the sanitizer literal set. logs/SLM_SAMPLE_APP_AUDIT.md at be6cc04 returns zero hits for SlmStreamSanitizer, medgemma, domain/slm, SlmEngine; it predates the sanitizer. The list was therefore read from the stale clone (319 lines, SHA-256 d6dfdd93…752f1ce0) and is provisional pending confirmation against the real tree.

Of 6,253 distinct literals, 6,233 exist in Gemma 4 and 20 do not. Carrying over cleanly: <pad> <eos> <bos> <unk> <mask> [multimodal]. Absent: <start_of_turn>, <end_of_turn>, <start_of_image>, <end_of_image>, <image_soft_token>, and <unused6227>..<unused6241>.

The sharp edge: THOUGHT_OPEN/THOUGHT_CLOSE = <unused94>/<unused95> still exist in Gemma 4, at ids 256006 and 256007, as ordinary reserved tokens with no channel meaning. Membership passes, nothing fails closed, span counters stay at zero, and the entire reasoning channel leaks to the clinician surface. The silent-failure mode and the real failure mode are indistinguishable under the current design.

STOP: GPU derivation complete, handoff at /tmp/claude-1000/-media-acps-twoTBDrive-finetuninggemma4/0168ae93-8d26-40a8-b67b-b434c62a048a/docs/design/gpu-control-token-handoff.md. Misplaced SaMD-App edit reverted, clone clean. Nothing else changed on this machine.