# Running Locally

This document describes how to set up [akvo-flow-services](https://github.com/akvo/akvo-flow-services) and [akvo-flow](https://github.com/akvo/akvo-flow/) so you can use akvo-flow-services in a completely local setting.

1. Follow the [installation notes](InstallationNotes.md)
2. Copy `config/config.edn` to a temporary `.edn` file (and keep it out of source control)
   1. `:config-folder` should point to a checked out `akvo-flow-server-config` repo
   2. `:username` should be `"reports@akvoflow.org"`
   3. `:password` should be the correct password for `:username`
3. In your local avko-flow repo, update the `flowServices` property in `GAE/war/WEB-INF/appengine-web.xml` to `<property name="flowServices" value="http://localhost:3000" />`
4. Start akvo-flow-services by running `lein clean && lein run config/my-config.edn`
5. Start akvo-flow (either via Eclipse or `ant runserver`)
6. You should now be able to generate reports in the akvo-flow dashboard.
