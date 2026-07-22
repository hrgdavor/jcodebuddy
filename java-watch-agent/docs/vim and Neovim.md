# vim and Neovim

**Vim** and **Neovim** both have a built-in option to automatically reload a file if it changes on disk.

## Enabling Automatic Reloads 

You can enable this behavior by setting the `autoread` option: 

- **Vim/Neovim Command:** `:set autoread`
- **In Configuration File:** Add `set autoread` to your `init.lua` or `.vimrc`. !

## How It Works (and the "Catch")

While `autoread` is powerful, it does not constantly "watch" the file in the background. Instead, it triggers a reload when: 

- You **switch focus** back to the editor (e.g., clicking into the window or returning from another app).
- You run an **external command** (like `:!ls`).
- You enter the **buffer**.

**Important:** If you have **unsaved changes** in your current buffer, Vim/Neovim will not automatically overwrite them. Instead, it will prompt you to choose whether to keep your changes or load the version from disk.


## Making it "Instant" (Automated Checking)

For terminal users, focus events might not always trigger `autoread` automatically. You can force the editor to check for disk changes more frequently using an **autocommand** in your config: 

**Neovim (Lua):**

```lua
vim.api.nvim_create_autocmd({ "FocusGained", "BufEnter", "CursorHold" }, {
    command = "checktime",
  })
```

**Vim (Vimscript):**

```
autocmd FocusGained,BufEnter,CursorHold * silent! checktime
```

This uses the `:checktime` command, which manually triggers the check for external changes. 

**Manual Reload**

If you just want to reload the file once without changing any settings:

- Use `:e` (or `:edit`) to reload the current file.
- Use `:e!` to **discard your unsaved changes** and force a reload from disk.