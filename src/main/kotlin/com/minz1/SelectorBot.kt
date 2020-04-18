package com.minz1

import com.jessecorbett.diskord.api.model.VoiceState
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.natpryce.konfig.*
import com.jessecorbett.diskord.dsl.bot
import java.io.File

class SelectorBot {
    private val botToken = Key("bot.token", stringType)
    private val serverId = Key("server.id", stringType)
    private val roleId = Key("role.id", stringType)
    private val voiceChannelId = Key("voice.channel.id", stringType)
    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File("selector.bot.properties"))

    public suspend fun runBot() {
        val vcId = config[voiceChannelId]
        val botToken = config[botToken]
        val roleId = config[roleId]
        val guildId = config[serverId]

        var voiceStates = ArrayList<VoiceState>()
        val guildClient = GuildClient(botToken, guildId)

        bot(botToken) {
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
}