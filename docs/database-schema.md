# Database Schema

This document extracts the real table structures from the provided SQL file for the Protanki server database.

## Tables

### USERS
- `ID` INTEGER (primary key, identity)
- `CRYSTALS` INTEGER (not null)
- `EQUIPMENT_HULL_ID` VARCHAR(255)
- `EQUIPMENT_PAINT_ID` VARCHAR(255)
- `EQUIPMENT_WEAPON_ID` VARCHAR(255)
- `PASSWORD` VARCHAR(255) (not null)
- `SCORE` INTEGER (not null)
- `USERNAME` VARCHAR(64) (not null, unique)

### INVITES
- `ID` INTEGER (primary key, identity)
- `CODE` VARCHAR(64) (not null, unique)
- `USERNAME` VARCHAR(64)

### DAILY_QUESTS
- `DTYPE` VARCHAR(31) (not null)
- `ID` INTEGER (primary key, identity)
- `COMPLETED` BOOLEAN (not null)
- `CURRENT` INTEGER (not null)
- `QUESTINDEX` INTEGER (not null)
- `NEW` BOOLEAN (not null)
- `REQUIRED` INTEGER (not null)
- `MODE` INTEGER
- `BONUS` INTEGER
- `MAP` VARCHAR(255)
- `USER_ID` INTEGER (foreign key to USERS.ID)

### DAILY_QUEST_REWARDS
- `REWARDINDEX` INTEGER (primary key part, not null)
- `COUNT` INTEGER (not null)
- `TYPE` INTEGER
- `QUEST_ID` INTEGER (primary key part, not null, foreign key to DAILY_QUESTS.ID)

### GARAGE_ITEMS
- `DTYPE` VARCHAR(31) (not null)
- `ITEMNAME` VARCHAR(255) (primary key part, not null)
- `ENDTIME` BIGINT
- `COUNT` INTEGER
- `MODIFICATIONINDEX` INTEGER
- `USER_ID` INTEGER (primary key part, not null, foreign key to USERS.ID)

## Indexes and Constraints
- Unique index on `USERS.USERNAME`.
- Unique index on `INVITES.CODE`.
- Foreign keys from `DAILY_QUESTS.USER_ID` → `USERS.ID`, `DAILY_QUEST_REWARDS.QUEST_ID` → `DAILY_QUESTS.ID`, and `GARAGE_ITEMS.USER_ID` → `USERS.ID`.
- Primary keys as indicated in each table definition.

## Sequences
- `SYSTEM_SEQUENCE_9163CC73_8C97_4AA6_A2D3_DCAF16306344` for `USERS.ID`.
- `SYSTEM_SEQUENCE_7E21DC48_0509_41CE_B13D_78F5ED6C9B5F` for `INVITES.ID`.
- `SYSTEM_SEQUENCE_8C0A19C9_2594_45CA_96BD_B6A4EA332489` for `DAILY_QUESTS.ID`.

