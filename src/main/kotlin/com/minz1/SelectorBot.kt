package com.minz1

import com.jessecorbett.diskord.api.exception.DiscordBadPermissionsException
import com.jessecorbett.diskord.api.model.GuildMember
import com.jessecorbett.diskord.api.model.Message
import com.jessecorbett.diskord.api.model.VoiceState
import com.jessecorbett.diskord.api.rest.PatchGuildMember
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.jessecorbett.diskord.dsl.bot
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.commands
import com.jessecorbett.diskord.util.DiskordInternals
import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import java.io.File
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SelectorBot {
    private val keyBotToken = Key("bot.token", stringType)
    private val keyServerId = Key("server.id", stringType)
    private val keyRoleId = Key("role.id", stringType)
    private val keyVcId = Key("voice.channel.id", stringType)
    private val keyBanRoleId = Key("ban.role.id", stringType)
    private val keyCmdPrefix = Key("command.prefix", stringType)
    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File("selector.bot.properties"))

    private val vcId = config[keyVcId]
    private val botToken = config[keyBotToken]
    private val roleId = config[keyRoleId]
    private val guildId = config[keyServerId]
    private val banRoleId = config[keyBanRoleId]
    private val commandPrefix = config[keyCmdPrefix]

    private val guildClient = GuildClient(botToken, guildId)

    public suspend fun runBot() {
        val voiceStates = HashMap<String, VoiceState>()
        val vcUsers = ArrayList<String>()
        var pollRunning = false
        var coolDown = false

        bot(botToken) {
            commands(commandPrefix) {
                command("overthrow") {
                    var poll: Message? = null
                    val usersInVc = ArrayList<String>(vcUsers)

                    when {
                        vcUsers.isNullOrEmpty() -> {
                            reply {
                                title = "A sad day..."
                                description = "Sadly, there is no revolution to be had here." +
                                        " Hopefully, the day will come!"
                            }
                        }
                        pollRunning -> {
                            GlobalScope.launch {
                                val message = reply {
                                    title = "Keep fighting!"
                                    description = "We are already overthrowing a tyrant! Keep fighting until it's over!"
                                }
                                delay(5000L)
                                message.delete()
                            }
                        }
                        coolDown -> {
                            GlobalScope.launch {
                                val message = reply {
                                    title = "Still preparing..."
                                    description = "We are spent from the previous revolution... after FIVE (5) minutes" +
                                            " have passed, we can begin anew!"
                                }
                                delay(5000L)
                                message.delete()
                            }
                        }
                        else -> {
                            val firstVcUser = guildClient.getMember(usersInVc[0])
                            val initiatingUser = guildClient.getMember(this@command.author.id)
                            poll = reply {
                                title = "Long live the revolution!"
                                description =
                                    "${initiatingUser.nickname ?: initiatingUser.user?.username ?: "a revolutionary"}" +
                                            " has begun a coup!\nTo successfully overthrow " +
                                            "${firstVcUser.nickname ?: firstVcUser.user?.username ?: "the tyrant"}," +
                                            " we MUST have MORE ✅ votes than ❌ votes in ONE (1) minute!"
                            }

                            poll.react("✅")
                            poll.react("❌")
                        }
                    }

                    if (poll != null) {
                        GlobalScope.launch {
                            pollRunning = true
                            val channelClient = ChannelClient(botToken, poll.channelId)
                            delay(60000L)
                            pollRunning = false
                            val currentVcUsers = vcUsers.toList()

                            val reactions = channelClient.getMessage(poll.id).reactions
                            var yesVotes = 0
                            var noVotes = 0

                            for (reaction in reactions) {
                                if (reaction.emoji.name == "✅") {
                                    yesVotes = reaction.count
                                } else if (reaction.emoji.name == "❌") {
                                    noVotes = reaction.count
                                }
                            }

                            poll.delete()

                            if (yesVotes > noVotes) {
                                GlobalScope.launch {
                                    val usersToBePunished = ArrayList<String>(usersInVc.size)

                                    for (userId in usersInVc) {
                                        if (! currentVcUsers.contains(userId)) {
                                            usersToBePunished.add(userId)
                                            usersInVc.remove(userId)
                                        }
                                    }

                                    val finalUserList = usersInVc.toList()

                                    GlobalScope.launch {
                                        for (userId in usersToBePunished) {
                                            guildClient.addMemberRole(userId, banRoleId)
                                        }
                                        delay(1800000L)
                                        for (userId in usersToBePunished) {
                                            guildClient.removeMemberRole(userId, banRoleId)
                                        }
                                    }

                                    try {
                                        if (finalUserList.isNotEmpty()) {
                                            for (userId in finalUserList) {
                                                dcUserFromVc(userId)
                                            }
                                        }

                                        val firstUser = guildClient.getMember(finalUserList[0])

                                        reply {
                                            title = "Long live the revolution!"
                                            description = "${firstUser.nickname ?: firstUser.user?.username ?: "the tyrant"}" +
                                                    " has been overthrown! Rejoice!"
                                            GlobalScope.launch {
                                                for (userId in finalUserList) {
                                                    guildClient.addMemberRole(userId, banRoleId)
                                                }
                                                delay(600000L)
                                                for (userId in finalUserList) {
                                                    guildClient.removeMemberRole(userId, banRoleId)
                                                }
                                            }
                                            GlobalScope.launch {
                                                coolDown = true
                                                delay(300000L)
                                                coolDown = false
                                            }
                                        }
                                    } catch (dbpe: DiscordBadPermissionsException) {
                                        reply {
                                            title = "A sad day..."
                                            description = "the tyrant couldn't be overthrown... their rank is too high."
                                        }
                                    }
                                }
                            } else {
                                reply {
                                    title = "A sad day..."
                                    description = "Sadly, there was not enough support for the revolution..." +
                                            " Hopefully, the day will come!"
                                }
                                GlobalScope.launch {
                                    coolDown = true
                                    delay(300000L)
                                    coolDown = false
                                }
                            }
                        }
                    }
                }
            }

            guildCreated { guild ->
                for (voiceState in guild.voiceStates) {
                    val userId = voiceState.userId
                    voiceStates[userId] = voiceState

                    if (voiceState.channelId == vcId) {
                        vcUsers.add(userId)
                    }
                }
            }

            userVoiceStateChanged { newVoiceState ->
                val userId = newVoiceState.userId

                when {
                    voiceStates.containsKey(userId) -> {
                        // the user is in voice already
                        var oldVoiceState = voiceStates[userId]

                        if (oldVoiceState!!.channelId == vcId) {
                            // the user moved out of our channel
                            guildClient.removeMemberRole(userId, roleId)
                            vcUsers.remove(userId)
                        }

                        if (newVoiceState.channelId.isNullOrBlank()) {
                            // the user disconnected from voice
                            guildClient.removeMemberRole(userId, roleId)
                            voiceStates.remove(userId)
                            vcUsers.remove(userId)
                        } else if (newVoiceState.channelId == vcId) {
                            // the user moved into our channel
                            guildClient.addMemberRole(userId, roleId)
                            vcUsers.add(userId)
                        }
                    }
                    else -> {
                        // the user is not already in voice
                        voiceStates[userId] = newVoiceState

                        if (newVoiceState.channelId == vcId) {
                            // give them the role if they connect to our channel
                            guildClient.addMemberRole(userId, roleId)
                            vcUsers.add(userId)
                        }
                    }
                }
            }
        }
    }

    @OptIn(DiskordInternals::class)
    private suspend fun dcUserFromVc(userId: String): Unit {
        val guildMember = guildClient.getMember(userId)

        val patchGuildMember = PatchGuildMember(guildMember.nickname, guildMember.roleIds, guildMember.isMute,
                guildMember.isDeaf, null)

        guildClient.patchRequest("/guilds/$guildId/members/${userId}", patchGuildMember, PatchGuildMember.serializer(), omitNullProperties = false)
    }
}