# Discord status

While a game runs, the launcher can show **Playing <project>** and **on Minecraft <version>**, with the time played, in
the player's Discord status. It is off until the player turns it on (Settings > Advanced > Discord), it is cleared
when the game closes, and it never touches friends or anything social.

## How it works

The launcher talks to the Discord app **on the same computer** over its local connection (a named pipe on Windows, a
socket in the runtime folder on macOS and Linux). Nothing is sent to a server of ours. If Discord is not running, or
the switch is off, or no application ID is set, nothing happens and nothing is reported as an error.

## Setting it up (owner, once)

Discord shows the name of an *application* after "Playing", so one has to exist:

1. Go to <https://discord.com/developers/applications> and choose **New Application**. Name it "Shardlight Client";
   that name is what players see.
2. Copy its **Application ID** from the General Information page. It is a number of 15 to 22 digits and it is
   public: it is not a secret, and no token or client secret is needed.
3. Give the id to the build, first match wins when the launcher runs:
   - Settings > Advanced > Discord application ID (per player, for testing);
   - `-Dshardlight.discordApplicationId=<id>`;
   - the build: the `SHARDLIGHT_DISCORD_APP_ID` Actions variable, or `-Pshardlight.discordApplicationId=<id>` locally
     (see RELEASING.md).

Until an id exists, the Settings switch says so and stays off.

## Checking by hand

1. Start Discord and the launcher, enter the id (or use a build that has it) and turn the switch on.
2. Press Play on a project. Within a few seconds your Discord profile shows "Playing Shardlight Client" with the
   project name and Minecraft version.
3. Close the game: the status goes away. Turn the switch off while playing: it goes away at once.
4. Quit Discord while playing and start it again: the status returns when the launcher next updates it.
