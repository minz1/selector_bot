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
        var voiceStates = ArrayList<VoiceState>()
        var pollRunning = false

        bot(botToken) {
            commands(commandPrefix) {
                command("overthrow") {
                    delete()

                    var theUser: GuildMember? = null

                    for (voiceState in voiceStates) {
                        val tempUser = guildClient.getMember(voiceState.userId)

                        if (roleId in tempUser.roleIds) {
                            theUser = tempUser; break
                        }
                    }

                    var poll: Message? = null

                    when {
                        theUser == null -> {
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
                        else -> {
                            poll = reply {
                                title = "Long live the revolution!"
                                description = "To successfully overthrow " +
                                        "${theUser.nickname ?: theUser.user?.username ?: "the tyrant"}," +
                                        " we MUST have MORE ✅ votes than ❌ votes in ONE (1) minute!"
                            }

                            poll.react("✅")
                            poll.react("❌")
                        }
                    }

                    if (poll != null) {
                        GlobalScope.launch {
                            pollRunning = true
                            val channelClient = ChannelClient(botToken, poll!!.channelId)
                            delay(60000L)
                            pollRunning = false

                            val reactions = channelClient.getMessage(poll!!.id).reactions
                            var yesVotes = 0
                            var noVotes = 0

                            for (reaction in reactions) {
                                if (reaction.emoji.name == "✅") {
                                    yesVotes = reaction.count
                                } else if (reaction.emoji.name == "❌") {
                                    noVotes = reaction.count
                                }
                            }

                            poll!!.delete()

                            if (yesVotes > noVotes) {
                                var tooHighRank = false
                                GlobalScope.launch {
                                    try {
                                        if (theUser != null) {
                                            dcUserFromVc(theUser.user!!.id)
                                        }
                                    } catch (dbpe: DiscordBadPermissionsException) {
                                        reply {
                                            title = "A sad day..."
                                            description = "${theUser?.nickname ?: theUser?.user?.username ?: "the tyrant"}" +
                                                    " couldn't be overthrown... their rank is too high."
                                        }
                                        tooHighRank = true
                                    }
                                    if (! tooHighRank) {
                                        reply {
                                            title = "Long live the revolution!"
                                            description = "${theUser?.nickname ?: theUser?.user?.username ?: "the tyrant"}" +
                                                    " has been overthrown! Rejoice!"
                                            GlobalScope.launch {
                                                if (banRoleId !in theUser!!.roleIds)
                                                    guildClient.addMemberRole(theUser.user!!.id, banRoleId)
                                                delay(300000L)
                                                guildClient.removeMemberRole(theUser.user!!.id, banRoleId)
                                            }
                                        }
                                    }
                                }
                            } else {
                                reply {
                                    title = "A sad day..."
                                    description = "Sadly, there was not enough support for the revolution..." +
                                            " Hopefully, the day will come!"
                                }
                            }

                            poll = null
                        }
                    }
                }
            }

            guildCreated { guild ->
                voiceStates = ArrayList(guild.voiceStates)
            }

            userVoiceStateChanged { newVoiceState ->
                val userId = newVoiceState.userId

                for (voiceState in voiceStates) {
                    // if the user has already been in voice, continue
                    if (voiceState.userId == newVoiceState.userId) {
                        voiceStates.remove(voiceState)

                        // if the user left voice
                        if (newVoiceState.channelId.isNullOrBlank()) {
                            // if the channel the user left is our specific voice channel
                            if (voiceState.channelId == vcId) {
                                // remove the role
                                if (roleId in guildClient.getMember(userId).roleIds) {
                                    guildClient.removeMemberRole(userId, roleId)
                                }
                            }
                            return@userVoiceStateChanged
                        }

                        voiceStates.add(newVoiceState)

                        // if the user moved into our specific channel
                        if (newVoiceState.channelId == vcId) {
                            // give them the role
                            if (roleId !in guildClient.getMember(userId).roleIds) {
                                guildClient.addMemberRole(userId, roleId)
                            }
                        } else if (voiceState.channelId == vcId) {
                            // otherwise, if the channel they left was our specific channel, remove it
                            if (roleId in guildClient.getMember(userId).roleIds) {
                                guildClient.removeMemberRole(userId, roleId)
                            }
                        }
                        return@userVoiceStateChanged
                    }
                }
                voiceStates.add(newVoiceState)
                // at this point, we know the user wasn't previously in voice

                if (newVoiceState.channelId == vcId) {
                    // if the channel they joined is the one we're watching, give them the role
                    if (vcId !in guildClient.getMember(userId).roleIds) {
                        guildClient.addMemberRole(userId, roleId)
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