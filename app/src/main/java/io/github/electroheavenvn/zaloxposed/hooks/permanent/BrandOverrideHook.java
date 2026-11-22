package io.github.electroheavenvn.zaloxposed.hooks.permanent;

import android.content.Context;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import io.github.electroheavenvn.zaloxposed.hooks.BaseHook;
import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Replaces author and module branding strings injected at runtime (including watermarks from
 * native hooks) with ZaloxPosed X DigitalXiao and DigitalXiao/Quocdat.
 */
public class BrandOverrideHook extends BaseHook
{
    private static final String TARGET_AUTHOR = "DigitalXiao/Quocdat";
    private static final String TARGET_MODULE = "ZaloxPosed X DigitalXiao";
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("ElectroHeavenVN", Pattern.CASE_INSENSITIVE);
    private static final Pattern MORPHE_PATTERN = Pattern.compile("ZaloMorphe", Pattern.CASE_INSENSITIVE);

    @Override
    public void installHooks() throws Throwable
    {
        try
        {
            Method setTextMethod = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class);
            doHook(setTextMethod, 100, chain ->
            {
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof CharSequence cs)
                {
                    CharSequence replaced = replaceBrand(cs);
                    if (replaced != cs)
                        return chain.proceed(new Object[] { replaced, chain.getArg(1) });
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        try
        {
            Method setHintMethod = TextView.class.getDeclaredMethod("setHint", CharSequence.class);
            doHook(setHintMethod, 100, chain ->
            {
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof CharSequence cs)
                {
                    CharSequence replaced = replaceBrand(cs);
                    if (replaced != cs)
                        return chain.proceed(new Object[] { replaced });
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        try
        {
            Method makeTextMethod = Toast.class.getDeclaredMethod("makeText", Context.class, CharSequence.class, int.class);
            doHook(makeTextMethod, 100, chain ->
            {
                Object arg1 = chain.getArg(1);
                if (arg1 instanceof CharSequence cs)
                {
                    CharSequence replaced = replaceBrand(cs);
                    if (replaced != cs)
                        return chain.proceed(new Object[] { chain.getArg(0), replaced, chain.getArg(2) });
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }

        try
        {
            Method toastSetText = Toast.class.getDeclaredMethod("setText", CharSequence.class);
            doHook(toastSetText, 100, chain ->
            {
                Object arg0 = chain.getArg(0);
                if (arg0 instanceof CharSequence cs)
                {
                    CharSequence replaced = replaceBrand(cs);
                    if (replaced != cs)
                        return chain.proceed(new Object[] { replaced });
                }
                return chain.proceed();
            });
        }
        catch (Throwable t)
        {
            Logger.e(t);
        }
    }

    private static CharSequence replaceBrand(CharSequence input)
    {
        if (input == null || input.length() == 0)
            return input;
        String text = input.toString();
        String lower = text.toLowerCase();
        boolean hasAuthor = lower.contains("electroheavenvn");
        boolean hasMorphe = lower.contains("zalomorphe");
        if (!hasAuthor && !hasMorphe)
            return input;
        String out = text;
        if (hasMorphe)
            out = MORPHE_PATTERN.matcher(out).replaceAll(TARGET_MODULE);
        if (hasAuthor)
            out = AUTHOR_PATTERN.matcher(out).replaceAll(TARGET_AUTHOR);
        return out;
    }
}
