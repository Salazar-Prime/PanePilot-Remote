package com.panepilot.remote.ssh

import com.panepilot.remote.model.ProjectAction
import org.json.JSONException
import org.json.JSONObject

class ProjectActionGateway(
    private val ssh: SshConnection,
    private val tmux: TmuxGateway
) {
    fun list(projectPath: String): List<ProjectAction> {
        require(projectPath.startsWith('/')) { "The project folder is invalid." }
        val actionsPath = projectPath.trimEnd('/') + "/.panepilot/actions.json"
        val quotedPath = TmuxGateway.shellQuote(actionsPath)
        val command =
            "if [ ! -e $quotedPath ]; then exit 3; fi; " +
                "if [ -L $quotedPath ] || [ ! -f $quotedPath ]; then " +
                "printf '%s\\n' '.panepilot/actions.json must be a regular file.' >&2; exit 4; fi; " +
                "panepilot_action_bytes=\$(wc -c < $quotedPath); " +
                "if [ \"\$panepilot_action_bytes\" -gt 1048576 ]; then " +
                "printf '%s\\n' '.panepilot/actions.json must be 1 MB or smaller.' >&2; exit 5; fi; " +
                "cat $quotedPath"
        val result = ssh.execute(command, maxOutputBytes = 1_048_576)
        if (result.exitCode == 3) return emptyList()
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.trim().ifBlank {
                "Project Actions could not be read."
            })
        }
        return parse(result.stdout)
    }

    fun run(projectId: String, projectPath: String, action: ProjectAction): ActionLaunch =
        tmux.runAction(projectId, projectPath, action)

    companion object {
        private const val MAX_ACTIONS = 100
        private val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )

        internal fun parse(value: String): List<ProjectAction> {
            try {
                val root = JSONObject(value)
                require(root.optInt("version", -1) == 1) {
                    ".panepilot/actions.json has an unsupported version."
                }
                val actions = root.optJSONArray("actions")
                    ?: throw IllegalArgumentException(".panepilot/actions.json has no Actions list.")
                require(actions.length() <= MAX_ACTIONS) {
                    ".panepilot/actions.json contains too many Actions."
                }
                val ids = mutableSetOf<String>()
                return buildList {
                    for (index in 0 until actions.length()) {
                        val candidate = actions.optJSONObject(index)
                            ?: throw IllegalArgumentException("An Action definition is invalid.")
                        val id = (candidate.opt("id") as? String)?.trim()?.lowercase()
                            ?: throw IllegalArgumentException("An Action identity is invalid.")
                        val name = (candidate.opt("name") as? String)?.trim()
                            ?: throw IllegalArgumentException("An Action name is invalid.")
                        val actionCommand = (candidate.opt("command") as? String)?.trim()
                            ?: throw IllegalArgumentException("An Action command is invalid.")
                        require(UUID_PATTERN.matches(id) && ids.add(id)) {
                            "An Action has an invalid or duplicate identity."
                        }
                        require(
                            name.isNotBlank() &&
                                name.length <= 80 &&
                                name.none { it.code in 0..31 || it.code == 127 }
                        ) { "An Action name is invalid." }
                        require(
                            actionCommand.isNotBlank() &&
                                actionCommand.length <= 4_096 &&
                                !actionCommand.contains('\u0000')
                        ) { "An Action command is invalid." }
                        add(ProjectAction(id, name, actionCommand))
                    }
                }
            } catch (error: JSONException) {
                throw IllegalArgumentException(".panepilot/actions.json is not valid JSON.", error)
            }
        }
    }
}
