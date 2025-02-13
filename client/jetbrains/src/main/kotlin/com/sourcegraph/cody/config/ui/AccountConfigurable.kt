package com.sourcegraph.cody.config.ui

import com.intellij.collaboration.util.ProgressIndicatorsProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import com.intellij.ui.dsl.gridLayout.VerticalAlign
import com.intellij.util.ui.EmptyIcon
import com.sourcegraph.cody.auth.ui.customAccountsPanel
import com.sourcegraph.cody.config.*
import com.sourcegraph.cody.config.notification.AccountSettingChangeActionNotifier
import com.sourcegraph.cody.config.notification.AccountSettingChangeContext
import com.sourcegraph.config.ConfigUtil
import java.awt.Dimension

class AccountConfigurable(val project: Project) :
    BoundConfigurable(ConfigUtil.SOURCEGRAPH_DISPLAY_NAME) {
    private val accountManager = service<CodyAccountManager>()
    private val accountsModel = CodyAccountListModel(project)
    private val defaultAccountHolder = project.service<CodyProjectDefaultAccountHolder>()
    private lateinit var dialogPanel: DialogPanel
    override fun createPanel(): DialogPanel {
        dialogPanel = panel {
            group("Authentication") {
                row {
                    customAccountsPanel(
                        accountManager,
                        defaultAccountHolder,
                        accountsModel,
                        CodyAccountDetailsProvider(
                            ProgressIndicatorsProvider().also { Disposer.register(disposable!!, it) },
                            accountManager,
                            accountsModel),
                        disposable!!,
                        true,
                        EmptyIcon.ICON_16) {
                        it.copy(server = it.server.copy())
                    }
                        .horizontalAlign(HorizontalAlign.FILL)
                        .verticalAlign(VerticalAlign.FILL)
                        .applyToComponent { this.preferredSize = Dimension(Int.MAX_VALUE, 200) }
                        .also {
                            DataManager.registerDataProvider(it.component) { key ->
                                if (CodyAccountsHost.KEY.`is`(key)) accountsModel else null
                            }
                        }
                }
                row {
                    link("Open ${ConfigUtil.CODY_DISPLAY_NAME} settings...") {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, CodyConfigurable::class.java)
                    }
                }
                row {
                    link("Open ${ConfigUtil.CODE_SEARCH_DISPLAY_NAME} settings...") {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, CodyConfigurable::class.java)
                    }
                }
            }
        }
        return dialogPanel
    }

    override fun reset() {
        dialogPanel.reset()
    }
    override fun apply() {
        val bus = project.messageBus
        val publisher = bus.syncPublisher(AccountSettingChangeActionNotifier.TOPIC)

        val oldDefaultAccount = defaultAccountHolder.account
        val oldUrl = oldDefaultAccount?.server?.url ?: ""

        var defaultAccount = accountsModel.defaultAccount
        val newAccessToken = defaultAccount?.let { accountsModel.newCredentials[it] }
        val defaultAccountRemoved = !accountsModel.accounts.contains(defaultAccount)
        if (defaultAccountRemoved) {
            defaultAccount = accountsModel.accounts.getFirstAccountOrNull()
        }
        val defaultAccountChanged = oldDefaultAccount != defaultAccount
        val accessTokenChanged =
            newAccessToken != null || defaultAccountRemoved || defaultAccountChanged

        val newUrl = defaultAccount?.server?.url ?: ""

        val serverUrlChanged = oldUrl != newUrl

        publisher.beforeAction(serverUrlChanged)
        super.apply()
        val context = AccountSettingChangeContext(serverUrlChanged, accessTokenChanged)
        CodyAuthenticationManager.getInstance().setDefaultAccount(project, defaultAccount)
        accountsModel.defaultAccount = defaultAccount
        publisher.afterAction(context)
    }
}
