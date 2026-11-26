package dk.langkilde.mindmill.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** A Material icon for a section: by its id, else by the Mac's SF Symbol name, else a plain dot. */
fun sectionIcon(id: String, sfSymbol: String = ""): ImageVector = when (id) {
    "home" -> Icons.Default.Home
    "today" -> Icons.Default.Today
    "inbox" -> Icons.Default.Inbox
    "notes" -> Icons.Default.EditNote
    "skills" -> Icons.Default.Extension
    "sharing" -> Icons.Default.Lock
    "decisions" -> Icons.Default.Verified
    "meetings" -> Icons.Default.Groups
    "discussions" -> Icons.Default.Forum
    "ideas" -> Icons.Default.TipsAndUpdates
    "research" -> Icons.Default.TravelExplore
    "facts" -> Icons.Default.Lightbulb
    "followups" -> Icons.Default.Checklist
    "references" -> Icons.Default.Bookmark
    "backlog" -> Icons.AutoMirrored.Filled.List
    "pullRequests", "prs" -> Icons.AutoMirrored.Filled.MergeType
    "sessions", "assistant" -> Icons.AutoMirrored.Filled.Chat
    "reminders" -> Icons.Default.Notifications
    "goals" -> Icons.Default.Flag
    "architecture" -> Icons.Default.AccountBalance
    "overview", "myWork" -> Icons.Default.Dashboard
    "factories" -> Icons.Default.PrecisionManufacturing
    "test", "testing" -> Icons.Default.Science
    "strategies" -> Icons.Default.Route
    "project", "projects" -> Icons.Default.Folder
    "knowledge" -> Icons.AutoMirrored.Filled.MenuBook
    "tags" -> Icons.Default.Sell
    "people" -> Icons.Default.People
    "voice" -> Icons.Default.Mic
    "allActions" -> Icons.Default.Bolt
    "connection" -> Icons.Default.Wifi
    "more" -> Icons.Default.MoreHoriz
    else -> symbolIcon(sfSymbol)
}

private fun symbolIcon(name: String): ImageVector = when {
    name.isBlank() -> Icons.Default.Circle
    "magnifyingglass" in name -> Icons.Default.Search
    name.startsWith("note") || "pencil" in name -> Icons.Default.EditNote
    "bubble" in name -> Icons.Default.Forum
    "checklist" in name || "checkmark" in name -> Icons.Default.Checklist
    "person" in name -> Icons.Default.Groups
    "lightbulb" in name -> Icons.Default.Lightbulb
    "bookmark" in name -> Icons.Default.Bookmark
    "hammer" in name || "wrench" in name -> Icons.Default.Build
    "flask" in name || "testtube" in name -> Icons.Default.Science
    "bell" in name -> Icons.Default.Notifications
    "calendar" in name || "sun" in name -> Icons.Default.Today
    "tray" in name -> Icons.Default.Inbox
    "flag" in name || "mountain" in name -> Icons.Default.Flag
    "gear" in name -> Icons.Default.Settings
    "folder" in name -> Icons.Default.Folder
    "list" in name -> Icons.AutoMirrored.Filled.List
    "doc" in name || "book" in name -> Icons.Default.Description
    else -> Icons.Default.Circle
}
