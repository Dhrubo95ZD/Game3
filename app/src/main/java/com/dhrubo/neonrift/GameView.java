package com.dhrubo.neonrift;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public final class GameView extends View {
    private enum State { CLASS_SELECT, BRIEFING, PLAYING, PAUSED, VICTORY, DEFEAT }

    private static final int INK = Color.rgb(7, 16, 19);
    private static final int TEAL = Color.rgb(45, 224, 216);
    private static final int GOLD = Color.rgb(247, 190, 84);
    private static final int CORAL = Color.rgb(238, 93, 75);
    private static final int PALE = Color.rgb(232, 246, 240);
    private static final int HERO_FRAMES = 8;
    private static final int ENEMY_FRAMES = 6;
    private static final int FX_FRAMES = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final Random random = new Random();
    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final ArrayList<Effect> effects = new ArrayList<>();
    private final ArrayList<FloatingText> floatingTexts = new ArrayList<>();
    private final SharedPreferences preferences;
    private final Vibrator vibrator;
    private final Bitmap classLineup;
    private final Bitmap arenaBackground;
    private final Bitmap slayerSheet;
    private final Bitmap sentinelSheet;
    private final Bitmap combatFxSheet;

    private State state = State.CLASS_SELECT;
    private float width, height, unit = 1f;
    private long lastFrame;
    private boolean rendering = true;
    private float playerX, playerY, facingX = 1f, facingY, moveX, moveY;
    private int playerHealth = 100, score, bestScore, wave, enemiesToSpawn, enemiesDefeated;
    private float spawnTimer, nextWaveTimer, missionTime, attackTimer, attackChainTimer;
    private float dashCooldown, dashTimer, heavyCooldown, hurtTimer, introBannerTimer;
    private float screenShake, flash;
    private boolean awaitingNextWave;
    private int comboStep, movePointer = -1;
    private float joyCenterX, joyCenterY, joyKnobX, joyKnobY;
    private float attackX, attackY, heavyX, heavyY, dashX, dashY;

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setBackgroundColor(INK);
        preferences = context.getSharedPreferences("frontier_save", Context.MODE_PRIVATE);
        bestScore = preferences.getInt("best_score", 0);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        classLineup = BitmapFactory.decodeResource(getResources(), R.drawable.class_lineup);
        arenaBackground = BitmapFactory.decodeResource(getResources(), R.drawable.arena_background);
        slayerSheet = BitmapFactory.decodeResource(getResources(), R.drawable.slayer_sheet);
        sentinelSheet = BitmapFactory.decodeResource(getResources(), R.drawable.sentinel_sheet);
        combatFxSheet = BitmapFactory.decodeResource(getResources(), R.drawable.combat_fx_sheet);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        width = w; height = h;
        unit = Math.min(width / 1280f, height / 720f);
        joyCenterX = 135f * unit; joyCenterY = height - 125f * unit;
        joyKnobX = joyCenterX; joyKnobY = joyCenterY;
        attackX = width - 112f * unit; attackY = height - 108f * unit;
        heavyX = width - 245f * unit; heavyY = height - 92f * unit;
        dashX = width - 205f * unit; dashY = height - 220f * unit;
        playerX = width * .5f; playerY = height * .62f;
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0f : Math.min(.034f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        if (rendering && state == State.PLAYING) update(dt);
        canvas.save();
        if (screenShake > 0f) canvas.translate((random.nextFloat() - .5f) * 14f * unit,
                (random.nextFloat() - .5f) * 14f * unit);
        if (state == State.CLASS_SELECT) drawClassSelect(canvas);
        else if (state == State.BRIEFING) drawBriefing(canvas);
        else drawBattle(canvas);
        canvas.restore();
        if (flash > 0f) {
            paint.setShader(null);
            paint.setColor(Color.argb((int) (Math.min(1f, flash) * 125), 225, 251, 246));
            canvas.drawRect(0, 0, width, height, paint);
        }
        if (rendering) postInvalidateOnAnimation();
    }

    private void startMission() {
        state = State.PLAYING;
        playerX = width * .5f; playerY = height * .63f;
        playerHealth = 100; score = 0; wave = 1; enemiesDefeated = 0; missionTime = 0f;
        attackTimer = attackChainTimer = dashCooldown = heavyCooldown = hurtTimer = 0f;
        enemies.clear(); effects.clear(); floatingTexts.clear();
        beginWave(1);
    }

    private void beginWave(int number) {
        wave = number;
        enemiesToSpawn = number == 1 ? 4 : number == 2 ? 6 : 5;
        spawnTimer = .45f; awaitingNextWave = false; nextWaveTimer = 0f; introBannerTimer = 2.1f;
    }

    private void update(float dt) {
        missionTime += dt;
        attackTimer = Math.max(0f, attackTimer - dt);
        attackChainTimer = Math.max(0f, attackChainTimer - dt);
        dashCooldown = Math.max(0f, dashCooldown - dt);
        heavyCooldown = Math.max(0f, heavyCooldown - dt);
        hurtTimer = Math.max(0f, hurtTimer - dt);
        introBannerTimer = Math.max(0f, introBannerTimer - dt);
        screenShake = Math.max(0f, screenShake - dt);
        flash = Math.max(0f, flash - dt * 3f);

        float speed = 245f * unit;
        if (dashTimer > 0f) {
            dashTimer -= dt; speed = 700f * unit;
            addEffect(4, playerX - facingX * 45f * unit, playerY - facingY * 30f * unit,
                    .15f, angleOf(facingX, facingY), 100f * unit);
        }
        playerX += moveX * speed * dt; playerY += moveY * speed * .72f * dt;
        if (Math.abs(moveX) + Math.abs(moveY) > .1f) { facingX = moveX; facingY = moveY; }
        playerX = clamp(playerX, 78f * unit, width - 78f * unit);
        playerY = clamp(playerY, height * .24f, height - 75f * unit);

        if (enemiesToSpawn > 0) {
            spawnTimer -= dt;
            if (spawnTimer <= 0f) {
                spawnEnemy(wave == 3 && enemiesToSpawn == 1);
                enemiesToSpawn--; spawnTimer = Math.max(.42f, 1.05f - wave * .13f);
            }
        }
        Iterator<Enemy> iterator = enemies.iterator();
        while (iterator.hasNext()) {
            Enemy enemy = iterator.next();
            if (enemy.dead) {
                enemy.deathTimer -= dt;
                if (enemy.deathTimer <= 0f) iterator.remove();
            } else updateEnemy(enemy, dt);
        }
        updateEffects(dt);
        if (!awaitingNextWave && enemiesToSpawn == 0 && livingEnemyCount() == 0) {
            if (wave >= 3) finishMission(true);
            else { awaitingNextWave = true; nextWaveTimer = 2.2f; }
        }
        if (awaitingNextWave && (nextWaveTimer -= dt) <= 0f) beginWave(wave + 1);
    }

    private void spawnEnemy(boolean elite) {
        int edge = random.nextInt(4); float x, y;
        if (edge == 0) { x = 40f * unit; y = height * (.3f + random.nextFloat() * .55f); }
        else if (edge == 1) { x = width - 40f * unit; y = height * (.3f + random.nextFloat() * .55f); }
        else if (edge == 2) { x = width * (.15f + random.nextFloat() * .7f); y = height * .23f; }
        else { x = width * (.15f + random.nextFloat() * .7f); y = height - 55f * unit; }
        int hp = elite ? 12 : 3 + wave;
        enemies.add(new Enemy(x, y, hp, elite));
    }

    private void updateEnemy(Enemy enemy, float dt) {
        enemy.anim += dt; enemy.hitFlash = Math.max(0f, enemy.hitFlash - dt);
        enemy.attackCooldown -= dt;
        float dx = playerX - enemy.x, dy = playerY - enemy.y;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > 78f * unit) {
            float inv = distance <= .001f ? 0f : 1f / distance;
            float speed = (enemy.elite ? 82f : 102f + wave * 8f) * unit;
            enemy.x += dx * inv * speed * dt; enemy.y += dy * inv * speed * .72f * dt;
            enemy.moving = true;
        } else {
            enemy.moving = false;
            if (enemy.attackCooldown <= 0f) {
                enemy.attackAnim = .42f; enemy.attackCooldown = enemy.elite ? .85f : 1.15f;
            }
        }
        if (enemy.attackAnim > 0f) {
            enemy.attackAnim -= dt;
            if (!enemy.attackApplied && enemy.attackAnim < .20f) {
                enemy.attackApplied = true; damagePlayer(enemy.elite ? 18 : 11);
            }
            if (enemy.attackAnim <= 0f) enemy.attackApplied = false;
        }
    }

    private void damagePlayer(int amount) {
        if (hurtTimer > 0f || dashTimer > 0f || state != State.PLAYING) return;
        playerHealth = Math.max(0, playerHealth - amount); hurtTimer = .72f;
        screenShake = .25f; flash = .45f;
        addEffect(3, playerX, playerY - 20f * unit, .28f, 0f, 125f * unit);
        floatingTexts.add(new FloatingText("-" + amount, playerX, playerY - 70f * unit, CORAL));
        vibrate(55);
        if (playerHealth <= 0) finishMission(false);
    }

    private void normalAttack() {
        if (state != State.PLAYING || attackTimer > 0f) return;
        comboStep = attackChainTimer <= 0f ? 0 : (comboStep + 1) % 3;
        attackTimer = comboStep == 2 ? .42f : .28f; attackChainTimer = .72f;
        autoFaceNearest(260f * unit);
        int fx = comboStep == 0 ? 0 : comboStep == 1 ? 1 : 2;
        addEffect(fx, playerX + facingX * 75f * unit, playerY + facingY * 48f * unit,
                .24f, angleOf(facingX, facingY), (comboStep == 2 ? 190f : 150f) * unit);
        hitEnemies(comboStep == 2 ? 165f : 138f, comboStep == 2 ? 3 : 2, false);
        vibrate(comboStep == 2 ? 35 : 18);
    }

    private void heavyAttack() {
        if (state != State.PLAYING || heavyCooldown > 0f) return;
        heavyCooldown = 5.5f; attackTimer = .58f; autoFaceNearest(330f * unit);
        addEffect(2, playerX + facingX * 95f * unit, playerY + facingY * 55f * unit,
                .38f, angleOf(facingX, facingY), 260f * unit);
        hitEnemies(235f, 6, true); screenShake = .18f; vibrate(50);
    }

    private void dash() {
        if (state != State.PLAYING || dashCooldown > 0f) return;
        if (Math.abs(moveX) + Math.abs(moveY) < .1f) autoFaceNearest(500f * unit);
        dashCooldown = 2.6f; dashTimer = .18f; vibrate(20);
    }

    private void autoFaceNearest(float range) {
        Enemy nearest = null; float nearestDistance = range;
        for (Enemy enemy : enemies) {
            if (enemy.dead) continue;
            float d = distance(playerX, playerY, enemy.x, enemy.y);
            if (d < nearestDistance) { nearest = enemy; nearestDistance = d; }
        }
        if (nearest != null && nearestDistance > 0f) {
            facingX = (nearest.x - playerX) / nearestDistance;
            facingY = (nearest.y - playerY) / nearestDistance;
        }
    }

    private void hitEnemies(float baseRange, int damage, boolean wide) {
        float range = baseRange * unit;
        for (Enemy enemy : enemies) {
            if (enemy.dead) continue;
            float dx = enemy.x - playerX, dy = enemy.y - playerY;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance > range) continue;
            float dot = distance <= .001f ? 1f : (dx * facingX + dy * facingY) / distance;
            if (!wide && dot < .05f) continue;
            enemy.health -= damage; enemy.hitFlash = .13f;
            float push = (wide ? 50f : 24f) * unit;
            enemy.x += facingX * push; enemy.y += facingY * push;
            addEffect(3, enemy.x, enemy.y - 28f * unit, .22f,
                    random.nextFloat() * 360f, 95f * unit);
            floatingTexts.add(new FloatingText(String.valueOf(damage), enemy.x,
                    enemy.y - 62f * unit, GOLD));
            if (enemy.health <= 0) defeatEnemy(enemy);
        }
    }

    private void defeatEnemy(Enemy enemy) {
        enemy.dead = true; enemy.deathTimer = .48f; enemiesDefeated++;
        score += enemy.elite ? 750 : 120 + wave * 25;
        addEffect(5, enemy.x, enemy.y - 20f * unit, .45f, 0f,
                (enemy.elite ? 190f : 130f) * unit);
    }

    private void finishMission(boolean victory) {
        if (victory) {
            score += Math.max(0, 3000 - (int) (missionTime * 18f));
            if (score > bestScore) {
                bestScore = score;
                preferences.edit().putInt("best_score", bestScore).apply();
            }
            state = State.VICTORY;
        } else state = State.DEFEAT;
        moveX = moveY = 0f; movePointer = -1; vibrate(victory ? 80 : 110);
    }

    private void updateEffects(float dt) {
        Iterator<Effect> fx = effects.iterator();
        while (fx.hasNext()) { Effect e = fx.next(); e.life -= dt; if (e.life <= 0f) fx.remove(); }
        Iterator<FloatingText> texts = floatingTexts.iterator();
        while (texts.hasNext()) {
            FloatingText t = texts.next(); t.life -= dt; t.y -= 38f * unit * dt;
            if (t.life <= 0f) texts.remove();
        }
    }

    private void addEffect(int frame, float x, float y, float life, float rotation, float size) {
        effects.add(new Effect(frame, x, y, life, rotation, size));
    }

    private int livingEnemyCount() {
        int count = 0; for (Enemy enemy : enemies) if (!enemy.dead) count++; return count;
    }

    private void drawClassSelect(Canvas canvas) {
        drawCover(canvas, classLineup);
        paint.setShader(new LinearGradient(0, 0, 0, height, Color.argb(70, 4, 11, 14),
                Color.argb(235, 4, 11, 14), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint); paint.setShader(null);
        small(canvas, "FRONTIER PROTOCOL  •  PRE-ALPHA", 42f * unit, 50f * unit,
                Paint.Align.LEFT, TEAL, 14f);
        title(canvas, "CHOOSE YOUR PATH", 42f * unit, 104f * unit, Paint.Align.LEFT, 42f);
        body(canvas, "Three disciplines. One frontier. Your record begins here.",
                44f * unit, 136f * unit, Paint.Align.LEFT, PALE, 16f);
        float top = height * .61f, bottom = height - 27f * unit, gap = 16f * unit;
        float cardW = (width - 84f * unit - gap * 2f) / 3f;
        String[] names = {"SLAYER", "GUNSLAYER", "MECHA"};
        String[] roles = {"Vanguard • Close combat", "Ranger • Precision assault",
                "Support • Medic & turrets"};
        for (int i = 0; i < 3; i++) {
            float left = 42f * unit + i * (cardW + gap);
            RectF card = new RectF(left, top, left + cardW, bottom);
            paint.setColor(i == 0 ? Color.argb(220, 8, 34, 34) : Color.argb(205, 7, 16, 19));
            canvas.drawRoundRect(card, 13f * unit, 13f * unit, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth((i == 0 ? 2.5f : 1f) * unit);
            paint.setColor(i == 0 ? TEAL : Color.argb(90, 232, 246, 240));
            canvas.drawRoundRect(card, 13f * unit, 13f * unit, paint); paint.setStyle(Paint.Style.FILL);
            small(canvas, i == 0 ? "AVAILABLE" : "COMING SOON", left + 18f * unit,
                    top + 27f * unit, Paint.Align.LEFT, i == 0 ? TEAL : GOLD, 11f);
            title(canvas, names[i], left + 18f * unit, top + 62f * unit, Paint.Align.LEFT, 25f);
            body(canvas, roles[i], left + 18f * unit, top + 88f * unit, Paint.Align.LEFT,
                    Color.argb(190, 232, 246, 240), 13f);
            if (i == 0) {
                float bx = card.right - 100f * unit, by = bottom - 31f * unit;
                paint.setColor(TEAL);
                canvas.drawRoundRect(new RectF(bx - 84f * unit, by - 22f * unit,
                        bx + 84f * unit, by + 22f * unit), 22f * unit, 22f * unit, paint);
                small(canvas, "SELECT", bx, by + 5f * unit, Paint.Align.CENTER, INK, 13f);
            }
        }
    }

    private void drawBriefing(Canvas canvas) {
        drawCover(canvas, arenaBackground);
        paint.setShader(new LinearGradient(0, 0, width, 0, Color.argb(242, 5, 17, 19),
                Color.argb(30, 5, 17, 19), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint); paint.setShader(null);
        float left = 58f * unit;
        small(canvas, "SLAYER // ORIENTATION MISSION", left, 68f * unit,
                Paint.Align.LEFT, TEAL, 13f);
        title(canvas, "HOLD THE\nRIDGELINE", left, 142f * unit, Paint.Align.LEFT, 48f);
        body(canvas, "Autonomous sentinels have broken their training limits.\nDisable three waves and secure the courtyard.",
                left, 260f * unit, Paint.Align.LEFT, PALE, 16f);
        briefingRow(canvas, left, 345f * unit, "MOVE", "Drag the field control");
        briefingRow(canvas, left, 395f * unit, "STRIKE", "Chain three timed attacks");
        briefingRow(canvas, left, 445f * unit, "BREAKLINE", "Wide heavy strike • 5.5s recharge");
        briefingRow(canvas, left, 495f * unit, "DASH", "Evade incoming impact • 2.6s recharge");
        RectF deploy = new RectF(left, height - 98f * unit, left + 330f * unit, height - 38f * unit);
        paint.setShader(new LinearGradient(deploy.left, 0, deploy.right, 0, TEAL,
                Color.rgb(91, 188, 170), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(deploy, 30f * unit, 30f * unit, paint); paint.setShader(null);
        small(canvas, "ENTER THE FRONTIER", deploy.centerX(), deploy.centerY() + 5f * unit,
                Paint.Align.CENTER, INK, 14f);
        body(canvas, "‹  Back to class selection", width - 42f * unit, height - 42f * unit,
                Paint.Align.RIGHT, PALE, 13f);
    }

    private void briefingRow(Canvas canvas, float x, float y, String key, String description) {
        paint.setColor(Color.argb(190, 8, 32, 33));
        canvas.drawRoundRect(new RectF(x, y - 25f * unit, x + 120f * unit, y + 16f * unit),
                8f * unit, 8f * unit, paint);
        small(canvas, key, x + 60f * unit, y + 1f * unit, Paint.Align.CENTER, TEAL, 12f);
        body(canvas, description, x + 142f * unit, y + 2f * unit, Paint.Align.LEFT,
                Color.argb(210, 232, 246, 240), 14f);
    }

    private void drawBattle(Canvas canvas) {
        drawCover(canvas, arenaBackground);
        paint.setColor(Color.argb(22, 2, 10, 8)); canvas.drawRect(0, 0, width, height, paint);
        ArrayList<RenderItem> items = new ArrayList<>(); items.add(new RenderItem(playerY, null));
        for (Enemy e : enemies) items.add(new RenderItem(e.y, e));
        Collections.sort(items, (a, b) -> Float.compare(a.y, b.y));
        for (RenderItem item : items) if (item.enemy == null) drawPlayer(canvas); else drawEnemy(canvas, item.enemy);
        for (Effect effect : effects) drawEffect(canvas, effect);
        for (FloatingText text : floatingTexts) title(canvas, text.text, text.x, text.y,
                Paint.Align.CENTER, 19f, withAlpha(text.color, (int) (255f * Math.min(1f, text.life * 2f))));
        drawHud(canvas); drawControls(canvas);
        if (introBannerTimer > 0f) drawWaveBanner(canvas);
        if (state == State.PAUSED) drawPauseOverlay(canvas);
        else if (state == State.VICTORY) drawResult(canvas, true);
        else if (state == State.DEFEAT) drawResult(canvas, false);
    }

    private void drawPlayer(Canvas canvas) {
        int frame;
        if (attackTimer > 0f) frame = heavyCooldown > 4.95f ? 6 : 3 + Math.min(2, comboStep);
        else if (Math.abs(moveX) + Math.abs(moveY) > .1f) frame = 1 + ((int) (missionTime * 8f) & 1);
        else frame = 0;
        drawShadow(canvas, playerX, playerY + 4f * unit, 52f * unit, 17f * unit);
        if (hurtTimer > 0f && ((int) (hurtTimer * 18f) & 1) == 0) paint.setAlpha(100);
        sheetFrame(canvas, slayerSheet, HERO_FRAMES, frame, playerX, playerY,
                160f * unit, 205f * unit, facingX < 0f, 0f);
        paint.setAlpha(255);
    }

    private void drawEnemy(Canvas canvas, Enemy enemy) {
        int frame = enemy.dead ? 5 : enemy.attackAnim > 0f ? (enemy.attackAnim > .22f ? 3 : 4) :
                enemy.moving ? 1 + ((int) (enemy.anim * 7f) & 1) : 0;
        float scale = enemy.elite ? 1.30f : 1f;
        float spriteH = 142f * unit * scale, spriteW = 180f * unit * scale;
        drawShadow(canvas, enemy.x, enemy.y + 4f * unit, 48f * unit * scale, 14f * unit * scale);
        if (enemy.hitFlash > 0f) paint.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP));
        sheetFrame(canvas, sentinelSheet, ENEMY_FRAMES, frame, enemy.x, enemy.y,
                spriteW, spriteH, playerX > enemy.x, enemy.dead ? 8f : 0f);
        paint.setColorFilter(null);
        if (!enemy.dead && (enemy.health < enemy.maxHealth || enemy.elite)) {
            float barW = 92f * unit * scale, top = enemy.y - spriteH * .70f;
            paint.setColor(Color.argb(175, 7, 16, 19));
            canvas.drawRoundRect(new RectF(enemy.x - barW / 2f, top, enemy.x + barW / 2f,
                    top + 7f * unit), 4f * unit, 4f * unit, paint);
            paint.setColor(enemy.elite ? GOLD : CORAL);
            canvas.drawRoundRect(new RectF(enemy.x - barW / 2f, top,
                    enemy.x - barW / 2f + barW * enemy.health / enemy.maxHealth,
                    top + 7f * unit), 4f * unit, 4f * unit, paint);
            if (enemy.elite) small(canvas, "WARDEN UNIT", enemy.x, top - 8f * unit,
                    Paint.Align.CENTER, GOLD, 9f);
        }
    }

    private void sheetFrame(Canvas canvas, Bitmap bitmap, int count, int frame, float x, float y,
                            float drawW, float drawH, boolean flip, float rotation) {
        source.set(frame * bitmap.getWidth() / count, 0,
                (frame + 1) * bitmap.getWidth() / count, bitmap.getHeight());
        destination.set(x - drawW / 2f, y - drawH, x + drawW / 2f, y + 14f * unit);
        canvas.save(); canvas.rotate(rotation, x, y);
        if (flip) canvas.scale(-1f, 1f, x, y);
        canvas.drawBitmap(bitmap, source, destination, paint); canvas.restore();
    }

    private void drawEffect(Canvas canvas, Effect effect) {
        float progress = effect.life / effect.maxLife;
        source.set(effect.frame * combatFxSheet.getWidth() / FX_FRAMES, 0,
                (effect.frame + 1) * combatFxSheet.getWidth() / FX_FRAMES, combatFxSheet.getHeight());
        float size = effect.size * (1.15f - progress * .15f);
        destination.set(effect.x - size / 2f, effect.y - size / 2f,
                effect.x + size / 2f, effect.y + size / 2f);
        paint.setAlpha((int) (255f * Math.min(1f, progress * 3f)));
        canvas.save(); canvas.rotate(effect.rotation, effect.x, effect.y);
        canvas.drawBitmap(combatFxSheet, source, destination, paint); canvas.restore(); paint.setAlpha(255);
    }

    private void drawShadow(Canvas canvas, float x, float y, float rx, float ry) {
        paint.setShader(new RadialGradient(x, y, rx, Color.argb(95, 0, 0, 0),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.save(); canvas.scale(1f, ry / rx, x, y); canvas.drawCircle(x, y, rx, paint);
        canvas.restore(); paint.setShader(null);
    }

    private void drawHud(Canvas canvas) {
        paint.setColor(Color.argb(210, 7, 16, 19));
        canvas.drawRoundRect(new RectF(22f * unit, 20f * unit, 365f * unit, 92f * unit),
                14f * unit, 14f * unit, paint);
        small(canvas, "SLAYER  •  LV. 01", 42f * unit, 46f * unit, Paint.Align.LEFT, TEAL, 11f);
        float hpL = 42f * unit, hpT = 59f * unit, hpW = 250f * unit;
        paint.setColor(Color.argb(85, 232, 246, 240));
        canvas.drawRoundRect(new RectF(hpL, hpT, hpL + hpW, hpT + 13f * unit), 7f * unit, 7f * unit, paint);
        paint.setShader(new LinearGradient(hpL, 0, hpL + hpW, 0, CORAL,
                Color.rgb(244, 153, 75), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(hpL, hpT, hpL + hpW * playerHealth / 100f,
                hpT + 13f * unit), 7f * unit, 7f * unit, paint); paint.setShader(null);
        small(canvas, playerHealth + " / 100", hpL + hpW + 52f * unit, hpT + 11f * unit,
                Paint.Align.CENTER, PALE, 10f);
        paint.setColor(Color.argb(210, 7, 16, 19));
        canvas.drawRoundRect(new RectF(width / 2f - 138f * unit, 20f * unit,
                width / 2f + 138f * unit, 76f * unit), 14f * unit, 14f * unit, paint);
        small(canvas, "RIDGELINE // WAVE " + wave + " OF 3", width / 2f, 44f * unit,
                Paint.Align.CENTER, GOLD, 12f);
        body(canvas, (livingEnemyCount() + enemiesToSpawn) + " sentinels active", width / 2f,
                65f * unit, Paint.Align.CENTER, PALE, 11f);
        paint.setColor(Color.argb(210, 7, 16, 19));
        canvas.drawRoundRect(new RectF(width - 240f * unit, 20f * unit, width - 22f * unit,
                76f * unit), 14f * unit, 14f * unit, paint);
        small(canvas, "FIELD SCORE", width - 42f * unit, 43f * unit,
                Paint.Align.RIGHT, Color.argb(160, 232, 246, 240), 10f);
        title(canvas, String.format(Locale.US, "%06d", score), width - 42f * unit,
                67f * unit, Paint.Align.RIGHT, 20f);
        paint.setColor(Color.argb(190, 7, 16, 19)); canvas.drawCircle(width - 38f * unit, 112f * unit, 19f * unit, paint);
        paint.setColor(PALE);
        canvas.drawRoundRect(new RectF(width - 46f * unit, 102f * unit, width - 41f * unit, 122f * unit), 2f, 2f, paint);
        canvas.drawRoundRect(new RectF(width - 35f * unit, 102f * unit, width - 30f * unit, 122f * unit), 2f, 2f, paint);
    }

    private void drawControls(Canvas canvas) {
        paint.setColor(Color.argb(72, 7, 16, 19)); canvas.drawCircle(joyCenterX, joyCenterY, 76f * unit, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * unit); paint.setColor(Color.argb(110, 232, 246, 240));
        canvas.drawCircle(joyCenterX, joyCenterY, 76f * unit, paint); paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(175, 45, 224, 216)); canvas.drawCircle(joyKnobX, joyKnobY, 33f * unit, paint);
        actionButton(canvas, attackX, attackY, 67f, "STRIKE", 0, 0f, TEAL);
        actionButton(canvas, heavyX, heavyY, 50f, "BREAK", 2, heavyCooldown, GOLD);
        actionButton(canvas, dashX, dashY, 43f, "DASH", 4, dashCooldown, PALE);
    }

    private void actionButton(Canvas canvas, float x, float y, float radius, String label,
                              int fxFrame, float cooldown, int accent) {
        float r = radius * unit;
        paint.setColor(Color.argb(195, 7, 24, 25)); canvas.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * unit); paint.setColor(accent);
        canvas.drawCircle(x, y, r, paint); paint.setStyle(Paint.Style.FILL);
        source.set(fxFrame * combatFxSheet.getWidth() / FX_FRAMES, 0,
                (fxFrame + 1) * combatFxSheet.getWidth() / FX_FRAMES, combatFxSheet.getHeight());
        destination.set(x - r * .67f, y - r * .67f, x + r * .67f, y + r * .67f);
        paint.setAlpha(180); canvas.drawBitmap(combatFxSheet, source, destination, paint); paint.setAlpha(255);
        if (cooldown > 0f) {
            float max = label.equals("BREAK") ? 5.5f : 2.6f;
            paint.setColor(Color.argb(180, 3, 10, 12));
            canvas.drawArc(new RectF(x - r, y - r, x + r, y + r), -90f,
                    360f * cooldown / max, true, paint);
            title(canvas, String.format(Locale.US, "%.1f", cooldown), x, y + 6f * unit,
                    Paint.Align.CENTER, 15f);
        }
        small(canvas, label, x, y + r + 18f * unit, Paint.Align.CENTER, PALE, 9f);
    }

    private void drawWaveBanner(Canvas canvas) {
        float alpha = Math.min(1f, introBannerTimer) * Math.min(1f, (2.1f - introBannerTimer) * 2f);
        paint.setColor(Color.argb((int) (185 * alpha), 7, 16, 19));
        canvas.drawRect(width * .32f, height * .39f, width * .68f, height * .55f, paint);
        small(canvas, wave == 3 ? "FINAL ENGAGEMENT" : "SENTINELS INBOUND", width / 2f,
                height * .44f, Paint.Align.CENTER, withAlpha(GOLD, (int) (255 * alpha)), 12f);
        title(canvas, "WAVE " + wave, width / 2f, height * .515f, Paint.Align.CENTER, 37f,
                withAlpha(PALE, (int) (255 * alpha)));
    }

    private void drawPauseOverlay(Canvas canvas) {
        paint.setColor(Color.argb(225, 5, 14, 16)); canvas.drawRect(0, 0, width, height, paint);
        small(canvas, "FIELD OPERATION SUSPENDED", width / 2f, height * .35f,
                Paint.Align.CENTER, TEAL, 13f);
        title(canvas, "PAUSED", width / 2f, height * .46f, Paint.Align.CENTER, 48f);
        body(canvas, "Tap anywhere to resume", width / 2f, height * .54f,
                Paint.Align.CENTER, PALE, 16f);
        body(canvas, "Hold your ground. The sentinels will wait.", width / 2f, height * .60f,
                Paint.Align.CENTER, Color.argb(150, 232, 246, 240), 12f);
    }

    private void drawResult(Canvas canvas, boolean victory) {
        paint.setColor(Color.argb(232, 5, 14, 16)); canvas.drawRect(0, 0, width, height, paint);
        small(canvas, victory ? "COURTYARD SECURED" : "TRAINING RUN ENDED", width / 2f,
                height * .25f, Paint.Align.CENTER, victory ? TEAL : CORAL, 13f);
        title(canvas, victory ? "RIDGELINE HELD" : "STAND DOWN", width / 2f,
                height * .36f, Paint.Align.CENTER, 46f);
        body(canvas, victory ? "The frontier records your first victory." : "Repair, review, and return stronger.",
                width / 2f, height * .42f, Paint.Align.CENTER, PALE, 15f);
        float top = height * .48f;
        paint.setColor(Color.argb(190, 13, 39, 39));
        canvas.drawRoundRect(new RectF(width * .30f, top, width * .70f, top + 86f * unit),
                14f * unit, 14f * unit, paint);
        resultStat(canvas, "SCORE", String.valueOf(score), width * .37f, top);
        resultStat(canvas, "DISABLED", String.valueOf(enemiesDefeated), width * .5f, top);
        resultStat(canvas, "BEST", String.valueOf(bestScore), width * .63f, top);
        float buttonTop = height - 105f * unit;
        paint.setColor(victory ? TEAL : GOLD);
        canvas.drawRoundRect(new RectF(width / 2f - 165f * unit, buttonTop,
                width / 2f + 165f * unit, buttonTop + 57f * unit), 29f * unit, 29f * unit, paint);
        small(canvas, victory ? "RETURN TO CLASS HALL" : "TRY AGAIN", width / 2f,
                buttonTop + 35f * unit, Paint.Align.CENTER, INK, 13f);
    }

    private void resultStat(Canvas canvas, String label, String value, float x, float top) {
        small(canvas, label, x, top + 28f * unit, Paint.Align.CENTER,
                Color.argb(160, 232, 246, 240), 10f);
        title(canvas, value, x, top + 62f * unit, Paint.Align.CENTER, 25f);
    }

    private void drawCover(Canvas canvas, Bitmap bitmap) {
        float imageRatio = bitmap.getWidth() / (float) bitmap.getHeight(), viewRatio = width / height;
        if (imageRatio > viewRatio) {
            int visible = (int) (bitmap.getHeight() * viewRatio), left = (bitmap.getWidth() - visible) / 2;
            source.set(left, 0, left + visible, bitmap.getHeight());
        } else {
            int visible = (int) (bitmap.getWidth() / viewRatio), top = (bitmap.getHeight() - visible) / 2;
            source.set(0, top, bitmap.getWidth(), top + visible);
        }
        destination.set(0, 0, width, height); canvas.drawBitmap(bitmap, source, destination, paint);
    }

    private void title(Canvas c, String text, float x, float y, Paint.Align align, float size) {
        title(c, text, x, y, align, size, PALE);
    }

    private void title(Canvas c, String text, float x, float y, Paint.Align align, float size, int color) {
        paint.setShader(null); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextAlign(align); paint.setTextSize(size * unit); paint.setColor(color);
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], x, y + i * size * 1.04f * unit, paint);
    }

    private void small(Canvas c, String text, float x, float y, Paint.Align align, int color, float size) {
        paint.setShader(null); paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setLetterSpacing(.12f); paint.setTextAlign(align); paint.setTextSize(size * unit);
        paint.setColor(color); c.drawText(text, x, y, paint); paint.setLetterSpacing(0f);
    }

    private void body(Canvas c, String text, float x, float y, Paint.Align align, int color, float size) {
        paint.setShader(null); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(align); paint.setTextSize(size * unit); paint.setColor(color);
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], x, y + i * size * 1.45f * unit, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index), y = event.getY(index); int id = event.getPointerId(index);
            if (state == State.CLASS_SELECT) { if (y > height * .55f && x < width * .36f) state = State.BRIEFING; return true; }
            if (state == State.BRIEFING) {
                if (y > height - 125f * unit && x < 440f * unit) startMission();
                else if (y > height - 90f * unit && x > width * .65f) state = State.CLASS_SELECT;
                return true;
            }
            if (state == State.PAUSED) { state = State.PLAYING; lastFrame = System.nanoTime(); return true; }
            if (state == State.VICTORY) { state = State.CLASS_SELECT; return true; }
            if (state == State.DEFEAT) { startMission(); return true; }
            if (state == State.PLAYING) {
                if (x > width - 78f * unit && y < 150f * unit) { state = State.PAUSED; moveX = moveY = 0f; return true; }
                if (distance(x, y, attackX, attackY) < 85f * unit) normalAttack();
                else if (distance(x, y, heavyX, heavyY) < 66f * unit) heavyAttack();
                else if (distance(x, y, dashX, dashY) < 60f * unit) dash();
                else if (x < width * .43f && movePointer == -1) { movePointer = id; updateJoystick(x, y); }
            }
        } else if (action == MotionEvent.ACTION_MOVE && state == State.PLAYING) {
            int pointerIndex = event.findPointerIndex(movePointer);
            if (pointerIndex >= 0) updateJoystick(event.getX(pointerIndex), event.getY(pointerIndex));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_CANCEL || event.getPointerId(index) == movePointer) {
                movePointer = -1; moveX = moveY = 0f; joyKnobX = joyCenterX; joyKnobY = joyCenterY;
            }
        }
        return true;
    }

    private void updateJoystick(float x, float y) {
        float dx = x - joyCenterX, dy = y - joyCenterY;
        float length = (float) Math.sqrt(dx * dx + dy * dy), limit = 70f * unit;
        if (length > limit) { dx = dx / length * limit; dy = dy / length * limit; }
        joyKnobX = joyCenterX + dx; joyKnobY = joyCenterY + dy;
        moveX = dx / limit; moveY = dy / limit;
    }

    public void pauseGame() { if (state == State.PLAYING) state = State.PAUSED; rendering = false; }
    public void resumeRendering() { rendering = true; lastFrame = System.nanoTime(); invalidate(); }

    private void vibrate(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else { /*noinspection deprecation*/ vibrator.vibrate(ms); }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2; return (float) Math.sqrt(dx * dx + dy * dy);
    }
    private static float angleOf(float x, float y) { return (float) Math.toDegrees(Math.atan2(y, x)); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static int withAlpha(int color, int a) { return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color)); }

    private static final class Enemy {
        float x, y, anim, attackCooldown = .7f, attackAnim, hitFlash, deathTimer;
        int health; final int maxHealth; final boolean elite;
        boolean moving, dead, attackApplied;
        Enemy(float x, float y, int hp, boolean elite) { this.x=x; this.y=y; health=hp; maxHealth=hp; this.elite=elite; }
    }
    private static final class Effect {
        final int frame; final float x, y, maxLife, rotation, size; float life;
        Effect(int f,float x,float y,float l,float r,float s){frame=f;this.x=x;this.y=y;life=l;maxLife=l;rotation=r;size=s;}
    }
    private static final class FloatingText {
        final String text; final float x; final int color; float y, life=.72f;
        FloatingText(String t,float x,float y,int c){text=t;this.x=x;this.y=y;color=c;}
    }
    private static final class RenderItem {
        final float y; final Enemy enemy;
        RenderItem(float y, Enemy enemy){this.y=y;this.enemy=enemy;}
    }
}
