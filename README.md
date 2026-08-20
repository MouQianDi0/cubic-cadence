# Cubic Cadence

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## NetEase data source

NetEase Cloud Music does not offer its multi-user Open API to individual
developers (individual accounts are limited to `ncm-cli`). The project therefore
uses the third-party reverse-engineered library
`NeteaseCloudMusicApiEnhanced/api-enhanced` as its data source.

That library relies on undocumented NetEase endpoints and is not officially
authorized. Using it for a public release carries account, terms-of-service and
legal compliance risk, and the endpoints may break without notice. This is a
deliberate trade-off given the individual-developer constraint, not an
official NetEase integration.

The Mod talks to a self-hosted `api-enhanced` service directly. Configure its
origin in `config/cubic-cadence.json` as `apiEnhancedBaseUrl` (for example
`http://localhost:3000`). The NetEase cookie returned after QR login is stored
encrypted with Windows DPAPI in `config/cubic-cadence/auth-session.dpapi`.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
