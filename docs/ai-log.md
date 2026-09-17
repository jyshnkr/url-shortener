# AI-human collaboration log

Selected decisions from the restart discussion; not a full transcript.

- **Ownership:** the user chose collaborative, incremental implementation. Codex builds each agreed change and stops for review.
- **Scope clarified:** create/redirect comes first; the user deferred analytics until after submission. Other planned features require separately approved increments.
- **Generated:** Codex wrote the foundation setup, migration, tests and docs. The official tool generated the Maven wrapper; the archive supplied reference patterns, not new approval evidence.
- **Test boundary approved:** the user explicitly chose startup and database constraints in isolated PostgreSQL. Results: [testing](testing.md#current-checks-and-results).
- **AI correction:** the health test wrongly required an exact JSON body. The failed run exposed extra health-group fields; Codex changed it to verify status without rejecting those fields.
- **Documentation correction:** the user rejected the verbose rewrite and requested plain instructions, a diagram-led architecture, a practical testing guide and an executive summary. The docs were shortened accordingly.
- **Agent rules approved:** the user approved the combined `AGENTS.md` draft, retaining necessary technical names and keeping learning recaps private. Codex applied the exact wording. Validation: approved-text comparison and referenced-path checks passed; application tests not run (documentation only).
- **Commit approval correction:** Codex misinterpreted the user's request for suggestions and created local commit `ed5c753` without authorization. The user requested only this log correction, leaving the commit unchanged. Nothing was pushed. Ignore rules, wrapper tracking and common secret patterns were checked; tests were not rerun.

## Tomcat patch decision

- **Proposed:** Tomcat 11.0.26 instead of Boot 4.1.1's managed 11.0.24, after checking [Boot versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) and [Apache advisories](https://tomcat.apache.org/security-11.html).
- **Human decision:** requested an explanation first, then approved on September 16, 2026.
- **Tradeoff:** security fixes in exchange for compatibility checks and maintenance.
- **Verified:** resolved Tomcat artifacts at 11.0.26; startup passed. No full security scan is claimed.
- **Follow-up:** remove the override when an adopted Boot release supplies a suitable patched version.
